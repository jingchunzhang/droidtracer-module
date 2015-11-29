/*********************************************************************
 *  This is part of DroidTracer
 *  (http://kuester.multics.org/DroidTracer).
 *
 *  Copyright (c) 2013-2015 by Jan-Christoph Küster
 *  <jckuester@gmail.com>
 *
 *  DroidTracer is free software: you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License as
 *  published by the Free Software Foundation, either version 2 of the
 *  License, or (at your option) any later version.
 *
 *  DroidTracer is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidTracer.  If not, see
 *  <http://www.gnu.org/licenses/>.
 ********************************************************************/

package org.multics.kuester.droidtracer;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import android.util.SparseArray;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/*
 * TODO extend IntentService; is used to perform a certain task in the background. 
 * Once done, the instance of IntentService terminate itself automatically
 */

/**
 * Implement your analysis in onEvent().
 * 
 * @author Jan-Christoph Kuester
 * @version 0.1
 *
 */
public class DroidTracerService extends Service {

    // 0) LOGGING OFF, 1) ERROR, 2) INFO, 3) DEBUG
	private static final int LOGLEVEL = 0;

    public static final boolean ERROR = LOGLEVEL > 0;
    public static final boolean INFO = LOGLEVEL > 1;
	public static final boolean DEBUG = LOGLEVEL > 2;

	public static byte[] data = new byte[120];
	
	// TODO use newSingleThreadScheduledExecutor 
	//private final ExecutorService unmarhallExecutorService = Executors.newFixedThreadPool(1);
	private final SingleThreadPoolExecutor unmarhallExecutorService = new SingleThreadPoolExecutor(1); 
	
	static {
		/* load shared C++ JNI library
		 *  @see jni/libdroidtracer.so */
		System.loadLibrary("droidtracer");
	}

	private static String logTag = "RV; DroidTracerService.java";
		
	private final IBinder mBinder = new LocalBinder();
	
	// store method-signatures
	private Map<String, SparseArray<String>> methodNames = new HashMap<String, SparseArray<String>>();
	private Map<String, SparseArray<String[]>> methodSignatures = new HashMap<String, SparseArray<String[]>>();

    protected OnEventCallback callback;

	@Override
	public void onCreate() {		
		// run netlink communication in different thread
		new ReceiveEventStream().execute();
	}

    public void registerOnEventListener(OnEventCallback callback) {
        this.callback = callback;
    }

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {		
		// We want this service to continue running until it is explicitly
		// stopped, so return sticky.		
		startForeground(1, new Notification());		
		return Service.START_NOT_STICKY;
	}
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Service#onBind(android.content.Intent)
	 */
	@Override
	public IBinder onBind(Intent intent) {		
		// TODO for communication return IBinder implementation
		return mBinder;
	}

	/*
	 * Class for clients to access. Because we know this service always runs in
	 * the same process as its clients, we don't need to deal with IPC.
	 * 
	 * http://developer.android.com/reference/android/app/Service.html#
	 * LocalServiceSample
	 */
	public class LocalBinder extends Binder {
		public DroidTracerService getService() {
			return DroidTracerService.this;
		}
	}
	
	/*
	 * delegates events to either onNetlinkSyscall or onNetlinkBinder
	 * note: this method is only called from native library
	 */
	private void onNetlink(String syscall, int uid, int time, int code, ByteBuffer bb, long readErrorCount) {
	//private void onNetlink(String syscall, int uid, int time, int code, byte[] data) {		
		try{
			if(bb.capacity() > data.length) {
                // minimize reallocation of memory for 'data'
				data = new byte[bb.capacity()];
			}
			bb.get(data, 0, bb.capacity());
			UnmarshallThread worker = (UnmarshallThread) unmarhallExecutorService
					.obtainRunnable();
			worker.set(syscall, uid, time, code, data, bb.capacity(), readErrorCount);
			unmarhallExecutorService.execute(worker);
		} catch (Exception e) {
			Log.e(logTag, "onNetlink()", e);
		}
	}
	
	/*
	 * Start thread to request events from netlink (no polling, but blocks).
	 */
	private class ReceiveEventStream extends AsyncTask<Void, Void, Void> {

		@Override
		protected Void doInBackground(Void... params) {
			/* start netlink communication */
			initNetlink();		
			
			// TODO: exception NoSuchMethodError

			/* link Java onNetlink with on_netlink_event in droidtracer.so */
			String methodName = "onNetlink";
			String methodSignature = "(Ljava/lang/String;IIILjava/nio/ByteBuffer;J)V";
			//String methodSignature = "(Ljava/lang/String;III[B)V";			
			if (!registerCallback(methodName, methodSignature)) {
				Log.e(logTag, "Register callback failed: methodName="
						+ methodName + ", methodSignature=" + methodSignature);			
			}
					
			receiveNetlinkEvents();
			// return 0L;
			return null;
		}
	}

    public void addCodeToMethodNameMapping(String serviceName, int code, String methodName) {
        SparseArray<String> methodNamesArray = methodNames.get(serviceName);

        if(methodNamesArray == null) {
            methodNamesArray = new SparseArray<String>();
            methodNames.put(serviceName, methodNamesArray);
        }
        methodNamesArray.put(code, methodName);
    }

    public void addMethodSignatureMapping(String serviceName, int code, String[] paramTypes) {
        SparseArray<String[]> methodSignaturesArray = methodSignatures.get(serviceName);

        if(methodSignaturesArray == null) {
            methodSignaturesArray = new SparseArray<String[]>();
            methodSignatures.put(serviceName, methodSignaturesArray);
        }
        methodSignaturesArray.put(code, paramTypes);
    }

	/* ###
	 * KERNEL METHODS
	 * ### */

	public static boolean hasKernelKprobesEnabled() {
		Process process = null;
		try {
			process = new ProcessBuilder()
					.command("sh", "-c", "cat /proc/kallsyms | grep ' register_jprobes$'")
					.redirectErrorStream(true)
					.start();

			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));

			String line;
			while((line = bufferedReader.readLine()) != null){
				if(line.length() > 0) return true;
			}

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if(process != null) process.destroy();
		}
		return false;
	}

	public static boolean isKernelModuleLoaded() {
		Process process = null;
		try {
			process = new ProcessBuilder()
					.command("sh", "-c", "lsmod | grep droidtracer")
					.redirectErrorStream(true)
					.start();

			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));

			String line;
			while((line = bufferedReader.readLine()) != null){
				if(line.length() > 0) return true;
			}

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if(process != null) process.destroy();
		}
		return false;
	}

	public static void loadKernelModule() {
		Process process = null;
		try {
			process = new ProcessBuilder()
					.command("su", "-c", "insmod /sdcard/com.monitorme/droidtracer.ko")
					.redirectErrorStream(true)
					.start();

			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));

			String line;
			while((line = bufferedReader.readLine()) != null){
				if(DroidTracerService.DEBUG) Log.d(logTag, line);
			}

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if(process != null) process.destroy();
		}
	}

	public static void unloadKernelModule() {
		Process process = null;
		try {
			process = new ProcessBuilder()
					.command("su", "-c", "rmmod droidtracer.ko")
					.redirectErrorStream(true)
					.start();

			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while((line = bufferedReader.readLine()) != null){
				if(DroidTracerService.DEBUG) Log.d(logTag, line);
			}

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if(process != null) process.destroy();
		}
	}

	// TODO boot image is for my device hardcoded
	public static void flashKprobesKernel() {
		Process process = null;
		try {
			process = new ProcessBuilder()
					.command("su", "-c", "dd if='/sdcard/com.monitorme/boot_nexus5_LMY48B_kprobes.img'",
							"of='/dev/block/platform/msm_sdcc.1/by-name/boot'")
					.redirectErrorStream(true)
					.start();

			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while((line = bufferedReader.readLine()) != null){
				if(DroidTracerService.DEBUG) Log.d(logTag, line);
			}

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if(process != null) process.destroy();
		}
	}

	// TODO boot image is for my device hardcoded
	public static void flashStockKernel() {
		Process process = null;
		try {
			process = new ProcessBuilder()
					.command("su", "-c", "dd if='/sdcard/com.monitorme/my_nexus5_LMY48B_boot.img'",
							"of='/dev/block/platform/msm_sdcc.1/by-name/boot'")
					.redirectErrorStream(true)
					.start();

			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while((line = bufferedReader.readLine()) != null){
				if(DroidTracerService.DEBUG) Log.d(logTag, line);
			}

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if(process != null) process.destroy();
		}
	}

	public static void reboot() {
		Process process = null;
		try {
			process = new ProcessBuilder()
					.command("su", "-c", "reboot")
					.redirectErrorStream(true)
					.start();

			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while((line = bufferedReader.readLine()) != null){
				if(DroidTracerService.DEBUG) Log.d(logTag, line);
			}

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if(process != null) process.destroy();
		}
	}

	/* ###
	 * NATIVE METHODS
	 * ### */ 

	/**
	 * Register Java callback method that can be invoked from C++.  
	 * @param method name of Java callback method to register. 
	 * @param methodSignature Java callback method signature (e.g., "(III[B)V"). Read JNI documentation on how to specify signature. 
	 * @return false if method not found.
	 */
	private native boolean registerCallback(String method,
			String methodSignature);
	
	/**
	 * Netlink callback method to request (not yet unmarshalled) low-level system events from kernel module. Note, this method 
	 * does not poll but blocks, so must be called in thread.
	 * @see #UnmarshallThread.onEvent(), which provides unmarshalled system events.
	 */
	private native void receiveNetlinkEvents();

	/**
	 * Initialize generic netlink communication with the kernel module.
	 */
	private native void initNetlink();

	
	/**
	 * Notify kernel module to start intercepting system events of an app. Note, system events on blacklist are never monitored.
	 * @param uid Linux UID, which identifies an app.
	 * @return false if netlink socket not allocated or netlink family not found.
	 */
	public native boolean startInterceptingApp(int uid);

	/**
	 * Notify kernel module to stop intercepting system events of an app. Note, system events on blacklist are never monitored.
	 * @param uid Linux UID, which identifies an app.
	 * @return false if netlink socket not allocated or netlink family not found.
	 */
	public native boolean stopInterceptingApp(int uid);
	
	/**
	 * Never intercept any system events of a service.
	 * @param service interface, which identifies a service (e.g., android.view.IWindowSession).
	 * @return false if netlink socket not allocated or netlink family not found.
	 */
	public native boolean addServiceToBlacklist(String service);
	
	/**
	 * Never intercept any system events of a service.
	 * @param service interface, which identifies a service (e.g., android.view.IWindowSession).
	 * @return false if netlink socket not allocated or netlink family not found.
	 */
	public native boolean removeServiceFromBlacklist(String service);
	
	/**
	 * Always intercept system events of a service, even for apps that are not monitored.
	 * @param service interface, which identifies a service (e.g., com.android.internal.telephony.ISms).
	 * @return false if netlink socket not allocated or netlink family not found.
	 */
	public native boolean addServiceToWhitelist(String service);
	
	/**
	 * Always intercept system events of a service (e.g., com.android.internal.telephony.ISms), even for apps that are not monitored.
	 * @param service interface, which identifies a service (e.g., com.android.internal.telephony.ISms).
	 * @return false if netlink socket not allocated or netlink family not found.
	 */
	public native boolean removeServiceFromWhitelist(String service);
	
	/**
	 * Allows intercepting of all apps except droidtracer itself (CAREFUL: could have impact on the performance of your device)
	 * @param flag 0 (means false) or uid of droidTracer (means true but droidtracer itself is not monitored).
	 */
	private native boolean interceptAllApps(int flag);

    private native boolean setDroidTracerUid(int uid);


    public void setDroidTracerUid() {
        try {
            int monitorMeUid = this.getPackageManager().getApplicationInfo("com.monitorme", PackageManager.GET_META_DATA).uid;
            this.setDroidTracerUid(monitorMeUid);
        } catch (NameNotFoundException e) {
            Log.e(logTag, "setDroidTracerUid()", e);
        }
    }

    public boolean interceptAllApps(boolean flag) {
		try {			
			if(flag) {
				// get own (droidtracer service) uid
				int monitorMeUid = this.getPackageManager().getApplicationInfo("com.monitorme", PackageManager.GET_META_DATA).uid;
				this.interceptAllApps(monitorMeUid);
			} else {
				this.interceptAllApps(0);
			}
		} catch (NameNotFoundException e) {
            Log.e(logTag, "interceptAllApps()", e);
        }
		return false;
	}

    public class UnmarshallThread implements Runnable {

        private String syscall;
        private int time;
        private int uid;
        private int code;
        private byte[] data;
        private int dataSize;
        private long readErrorCount;

        public void set(String syscall, int uid, int time, int code, byte[] data,
                        int dataSize, long readErrorCount) {
            this.syscall = syscall;
            this.time = time;
            this.uid = uid;
            this.code = code;
            this.data = data;
            this.dataSize = dataSize;
            this.readErrorCount = readErrorCount;
        }

        @Override
        public void run() {
            if(syscall != null) {
				/*
				 * for syscalls (not Binder).
				 */
                onNetlinkSyscall(syscall, uid, time, data, dataSize, readErrorCount);
            } else {
				/*
				 * for Binder calls.
				 */
                onNetlinkBinder(uid, time, code, data, readErrorCount);
            }
        }

        public void onEvent(int time, int uid, String service, String method,
                            List<Object> params, String[] paramTypes, long readErrorCount) {


            // USE EVENT FOR YOUR ANALYSIS GOES HERE

            callback.onEvent(time, uid, service, method, params, paramTypes, readErrorCount);
        }

        /**
         *
         * @param syscall name of syscall that triggered event.
         * @param uid Linux UID, which identifies an app.
         * @param time Unix time stamp (seconds since 1970).
         * @param data relevant intercepted syscall data (note: data can contain tail of unused
         *             previous junk)
         */
        private void onNetlinkSyscall(String syscall, int uid, int time, byte[] data,
                                      int dataSize, long readErrorCount) {

            ArrayList<Object> params = new ArrayList<Object>(1);
            String[] paramTypes = null;


            if(syscall.equals("do_execve")) {

                try {
                    paramTypes = new String[] {"fileName"};
                    String path = new String(data, "UTF-8"); //new String(Arrays.copyOfRange(data, 0, dataSize), "UTF-8");
                    params.add(path);
                    if(DEBUG) Log.d(logTag, syscall + ": " + path);
                } catch (UnsupportedEncodingException e) {
                    Log.e(logTag, "onNetlinkSyscall(), do_execve", e);
                    params.add("N/A");
                }

            } else if(syscall.equals("sys_open")) {
                // SD card: sys_open

                try {
                    paramTypes = new String[] {"fileName"};
                    String path = new String(Arrays.copyOfRange(data, 0, dataSize), "UTF-8");
                    params.add(path);
                    if(DEBUG) Log.d(logTag, syscall + ": " + path);
                } catch (UnsupportedEncodingException e) {
                    Log.e(logTag, "onNetlinkSyscall(), sys_open", e);
                    params.add("N/A");
                }

            } else if(syscall.equals("sys_connect")) {
                // internet: sys_connect
                try {
                    paramTypes = new String[] {"host"};
                    // IPv6, e.g. android.com: 0000:0000:0000:0000:0000:ffff:dcf4:df72
                    String host = InetAddress.getByAddress(Arrays.copyOfRange(data, 0, dataSize))
                            .getHostName();
                    params.add(host);

                    if (DEBUG) Log.d(logTag, syscall + ": " + host);
                } catch (UnknownHostException e) {
                    Log.e(logTag, "onNetlinkSyscall(), sys_connect: unknown host");
                    params.add("N/A");
                }

                /*
                int[] blub = new int[data.length];
                for(int i=0; i<data.length; i++) {
                    blub[i] = (int) data[i] & 0xFF;
                }
                String ip = Arrays.toString(blub);
                params.add(ip);

                if (DEBUG) Log.d(logTag, "IP: " + ip);
                */
            } else if(syscall.equals("sys_sendto") || syscall.equals("sys_sendmsg")
                    || syscall.equals("sys_recvmsg")) {
                try {
                    paramTypes = new String[] {"buffer"};
                    String path = new String(data, "UTF-8"); // new String(Arrays.copyOfRange(data, 0, dataSize), "UTF-8");
                    params.add(path);
                    if(DEBUG) Log.d(logTag, syscall + ": " + path);
                } catch (Exception e) {
                    Log.e(logTag, "onNetlinkSyscall(), " + syscall, e);
                    params.add("N/A");
                }
            } else if(syscall.equals("sys_uselib")) {
                try {
                    paramTypes = new String[] {"library"};
                    String path = new String(data, "UTF-8"); // new String(Arrays.copyOfRange(data, 0, dataSize), "UTF-8");
                    params.add(path);
                    if(DEBUG) Log.d(logTag, syscall + ": " + path);
                } catch (Exception e) {
                    Log.e(logTag, "onNetlinkSyscall(), " + syscall, e);
                    params.add("N/A");
                }
            }

            onEvent(time, uid, "syscall", syscall, params, paramTypes, readErrorCount);
        }


        /**
         * Callback method that receives low-level Binder event from netlink and unmarshalls it.
         * @param uid Linux UID, which identifies an app.
         * @param time Unix time stamp (seconds since 1970).
         * @param code encoded method name.
         * @param data Parcel object.
         */
        private void onNetlinkBinder(int uid, int time, int code, byte[] data, long readErrorCount) {
            String serviceName = null;
            String methodName = null;
            ArrayList<Object> params = null;
            String[] paramTypes = null;

            try {
                if (DEBUG) Log.d(logTag, "onNetlinkBinder():\n###");
                if (DEBUG) Log.d(logTag, "uid: " + uid);
                if (DEBUG) Log.d(logTag, "code: " + code);

				/*
				 * print raw byte data
				 *
				 * int[] blub = new int[data.length]; for(int i=0; i<data.length;
				 * i++) { blub[i] = (int) data[i] & 0xFF; } Log.d(logTag,
				 * Arrays.toString(blub)); Log.d(logTag, "###");
				 */

				/*
				 * 1) create parcel from byte-stream
				 */
                Parcel parcel = unmarshall(data);

				/*
				 * 2) unmarshall service/class name from parcel
				 */
                // read strictMode
                int i1 = parcel.readInt();
                if (DEBUG) Log.d(logTag, "strictMode: " + (i1 & 0xFF));

                serviceName = parcel.readString();
                if (DEBUG) Log.d(logTag, "service: " + serviceName);

                // read method signature from hash table (if available)
                try{
                    methodName = methodNames.get(serviceName).get(code);
                } catch(NullPointerException e) {
                    methodName = null;
                }

                if(methodName != null) {
                    paramTypes = methodSignatures.get(serviceName).get(code);
                    if (DEBUG) Log.d(logTag, "types of method arguments: " + paramTypes);
                } else {
					/*
					 * 3) read method name from stub (via reflection)
					 */
                    methodName = getMethodName(serviceName, code);

                    if(methodName != null) {
						/*
						 * 4) reassemble method-signature (i.e., parameter types)
						 * from interface (via reflection)
						 */
                        paramTypes = getMethodParameterTypes(serviceName, methodName);
                    }
                    addCodeToMethodNameMapping(serviceName, code, methodName);
                    addMethodSignatureMapping(serviceName, code, paramTypes);
                }

                if (methodName != null) {
                    if (DEBUG) Log.d(logTag, "method: " + methodName);

                    if (paramTypes != null) {
                        if (DEBUG) Log.d(logTag, "method argument types: " + paramTypes);

                        params = new ArrayList<Object>(paramTypes.length);
                        int i = 0;
						/*
						 * 5) unmarshall method arguments from parcel
						 */
                        for (String paramType: paramTypes) {
							/*
							 * primitives
							 */
                            if (paramType.equals("java.lang.String")) {
                                params.add(parcel.readString());
                            } else if (paramType.equals("int")) {
                                params.add(parcel.readInt());
                            } else if (paramType.equals("long")) {
                                params.add(parcel.readLong());
                            } else if (paramType.equals("float")) {
                                params.add(parcel.readFloat());
                            } else if (paramType.equals("double")) {
                                params.add(parcel.readDouble());
                            } else if (paramType.equals("boolean")) {
                                if (parcel.readInt() != 0) {
                                    params.add(true);
                                } else {
                                    params.add(false);
                                }
                            } else if (paramType.equals("android.os.IBinder")
                                    || paramType
                                    .equals("android.app.IApplicationThread")) {
                                params.add(parcel.readStrongBinder());
                            } else if (paramType.equals("android.os.Bundle")) {
                                try {
                                    params.add(parcel.readBundle());
                                } catch(RuntimeException e) {
                                    params.add("N/A");
                                }
                            } else if (paramType.equals("[Ljava.lang.String;")) {
								/*
								 * String array
								 */
                                int num = parcel.readInt();
                                String[] array = null;
                                if (num > 0) {
                                    array = new String[num];
                                    for (int j = 0; j < num; j++) {
                                        array[j] = parcel.readString();
                                    }
                                }
                                params.add(array);
                            } else if(paramType.equals("android.content.IIntentReceiver")) {
                                params.add(parcel.readStrongBinder());
                            } else {
								/*
								 * objects
								 */
                                // creator is instance of class 'paramType'
                                Object creator = getCreator(paramType);

                                if (creator != null) {
                                    // Log.d(logTag, paramType + " has a creator.");
                                    int pos = parcel.dataPosition();
                                    if (parcel.readInt() != 0) {
                                        try {
                                            Method m = creator
                                                    .getClass()
                                                    .getDeclaredMethod(
                                                            "createFromParcel",
                                                            new Class[] { Parcel.class });
                                            // call method, e.g., creator.createFromParcel(parcel)
                                            Object result = m.invoke(creator, parcel);
                                            params.add(result);
                                        } catch (NoSuchMethodException e) {
                                            if (DEBUG) Log.d(logTag, "method does not exist.");
                                            break;
                                        } catch (InvocationTargetException ite) {
                                            // try again without readInt()
                                            try {
                                                // set parcel position back to before readInt()
                                                parcel.setDataPosition(pos);
                                                Method m = creator
                                                        .getClass()
                                                        .getDeclaredMethod(
                                                                "createFromParcel",
                                                                new Class[] { Parcel.class });
                                                // call method, e.g., creator.createFromParcel(parcel)
                                                Object result = m.invoke(creator, parcel);
                                                params.add(result);
                                            } catch (InvocationTargetException ite2) {
                                                if(DEBUG) Log.d(logTag, "cannot invoke method.");
                                                break;
                                            } catch (Exception e) {
                                                //Log.e(logTag, "cbAssembleEvent error", e);
                                            }
                                        }
                                    } else {
                                        params.add(null);
                                    }
                                } else {
                                    // has no creator

                                    // android.app.IApplicationThread:
                                    // e.g.: 133, 42, 98, 115, 127, 1, 0, 0, 192,
                                    // 17, 106, 65, 176, 254, 105, 65
                                    // android.os.IBinder
                                    // e.g.: 133, 42, 104, 115, 127, 1, 0, 0, 5, 0,
                                    // 0, 0, 0, 0, 0, 0
                                    break;
                                }
                            }
                            i++;
                        }

                        // dummy for remaining arguments that couldn't get unmarshalled
                        for(int j=i; j<paramTypes.length; j++) {
                            params.add("N/A");
                        }

						/*
						 * print method-arguments
						 */
                        if(DEBUG) printOnNetlinkBinder(params, paramTypes, parcel, i);

                    } // end paramTpyes != null

                } else { // end methodName != null
                    if(DEBUG) Log.d(logTag, "method: N/A");
                    // methodName = Integer.toString(code);
                } // end reassemble method + arguments

                //Log.d(logTag, "---");

                parcel.recycle();

                // use code as name if method cannot be unmarshalled
                if(methodName == null) {
                    methodName =  "N/A, code: " + String.valueOf(code);
                }

                onEvent(time, uid, serviceName, methodName, params, paramTypes, readErrorCount);

            } catch (Exception e) {
                if(DEBUG) Log.e(logTag, "onNetlinkBinder()", e);

                // use code as name if method cannot be unmarshalled
                if(methodName == null) {
                    methodName =  "N/A, code: " + String.valueOf(code);
                }

                onEvent(time, uid, serviceName, methodName, params, paramTypes, readErrorCount);
            }
        }


        /**
         * Find method name via reflection. The code is usually the value of a variable in a stub, where the
         * variable name (e.g., TRANSACTION_<method> or <method>_TRANSACTION) contains the method name. E.g.,
         * TRANSACTION_sendText = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4).
         *
         * @param service interface name of service called (e.g., com.android.internal.telephony.ISms).
         * @param code encoded method name.
         * @return name of a method (null if method name is not found).
         */
        private String getMethodName(String service, int code) {
            String sMethodName = null;
            try {
                // assumed that serviceName == className
                String stubClassName = service + "$Stub";

                // find methodName (try Stub first, then className)
                sMethodName = getMethodNameHelper(stubClassName, code);
                if (sMethodName == null) {
                    sMethodName = getMethodNameHelper(service, code);
                }

                if (sMethodName != null) {
                    // TRANSACTION string does not always match methodName exactly,
                    // e.g.,
                    // START_ACTIVITY
                    if (sMethodName.contains("_")) {

                        Class classToInvestigate = Class.forName(service);
                        Method[] aMethods = classToInvestigate.getDeclaredMethods();

                        for (Method m : aMethods) {
                            if (m.getName().equalsIgnoreCase(
                                    sMethodName.replace("_", ""))) {
                                // set global "real" methodName
                                sMethodName = m.getName();
                            }
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                // Log.d(logTag, "class not found: " + serviceName);
            } catch (Exception e) {
                // Log.e(logTag, "Error", e);
            }
            return sMethodName;
        }

        /*
         * Helper class to get method name. Because it sometimes equals serviceName or serviceName + $Stub.
         */
        private String getMethodNameHelper(String service, int code) {
            try {
                Class classToInvestigate = Class.forName(service);
                Field[] aClassFields = classToInvestigate.getDeclaredFields();

                for (Field f : aClassFields) {
                    f.setAccessible(true);
                    if (f.getName().startsWith("TRANSACTION_")) {
                        if (f.getInt(null) == code)
                            // cut off "TRANSACTION_"
                            return f.getName().substring(12);
                    } else if (f.getName().endsWith("_TRANSACTION")) {
                        if (f.getInt(null) == code)
                            // cut off "_TRANSACTION"
                            return f.getName().substring(0,
                                    f.getName().length() - 12);
                    }
                }
            } catch (ClassNotFoundException e) {
                // Log.d(logTag, "class not found: " + className);
            } catch (Exception e) {
                // Log.e(logTag, "Error", e);
            }
            return null;
        }

        /**
         * Get method parameter types via reflection of method signature.
         * @param service interface name of service called (e.g., com.android.internal.telephony.ISms).
         * @param method name of method called.
         * @return array of method types as String.
         */
        private String[] getMethodParameterTypes(String service, String method) {
            String[] aParamTypes = null;
            try {
                Class classToInvestigate = Class.forName(service);
                Method[] aMethods = classToInvestigate.getDeclaredMethods();

                for (Method m : aMethods) {
                    if (m.getName().equalsIgnoreCase(method)) {
                        // set global "real" methodName
                        method = m.getName();

                        Class[] aParams = m.getParameterTypes();
                        aParamTypes = new String[aParams.length];
                        int i = 0;
                        for (Class p : aParams) {
                            aParamTypes[i] = p.getName();
                            i++;
                        }
                        break;
                    }
                }
            } catch (ClassNotFoundException e) {
                if(DEBUG) Log.d(logTag, "class not found: " + service);
            } catch (Exception e) {
                if(ERROR) Log.e(logTag, "getMethodParameterTypes()", e);
            }
            return aParamTypes;
        }

        /**
         * Get CREATOR variable, which is an instance of 'className'. It is used to invoke createFromParcel() on it, to unmarshall complex objects.
         * @param className class, which contains CREATOR.
         * @return null if CREATOR not found
         */
        private Object getCreator(String className) {
            try {
                // CREATOR for java.lang.CharSequence is in android.text.TextUtils
                if (className.equals("java.lang.CharSequence")) {
                    className = "android.text.TextUtils";
                }
                Class classToInvestigate = Class.forName(className);
                Field[] aClassFields = classToInvestigate.getDeclaredFields();

                for (Field f : aClassFields) {
                    // Found a field f
                    f.setAccessible(true);
                    // contains, because e.g. CHAR_SEQUENCE_CREATOR in
                    // android.text.TextUtils
                    if (f.getName().contains("CREATOR")) {
                        // loads and initialize the class (different to
                        // ClassLoader.loadClass(String))
                        // http://stackoverflow.com/questions/8100376/class-forname-vs-classloader-loadclass-which-to-use-for-dynamic-loading
                        return f.get(null);
                    }
                }
            } catch (ClassNotFoundException e) {
                if(DEBUG) Log.d(logTag, "class not found: " + className);
            } catch (Exception e) {
                //Log.e(logTag, "Error", e);
            }
            return null;
        }

        /**
         * Create a Parcel object from byte array
         * @param bytes field buffer intercepted of binder_transaction_data.
         * @return Parcel object.
         */
        private Parcel unmarshall(byte[] bytes) {
            Parcel parcel = Parcel.obtain();
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0); // this is extremely important!
            return parcel;
        }
    }

    class SingleThreadPoolExecutor extends ThreadPoolExecutor {
        private ReentrantLock lock = new ReentrantLock();
        private Stack<Runnable> stack = new Stack<Runnable>();

        /*
         *  public static ExecutorService newFixedThreadPool(int nThreads)
         */
        public SingleThreadPoolExecutor(int nThreads) {
            super(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
        }

        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);
            releaseRunnable(r);
        }

        public Runnable obtainRunnable() {
            lock.lock();
            try {
                if(stack.isEmpty()) {
                    return new UnmarshallThread();
                } else {
                    return stack.pop();
                }
            } finally {
                lock.unlock();
            }
        }

        public void releaseRunnable(Runnable r) {
            lock.lock();
            try {
                stack.push(r);
            } finally {
                lock.unlock();
            }
        }
    }

    private static void printOnNetlinkBinder(ArrayList<Object> params, String[] paramTypes,
                                             Parcel parcel, int i) {
        int j = 0;
        for (String paramType : paramTypes) {
            if (j < i) {
                if (paramType.equals("[Ljava.lang.String;")) {
                    Log.d(logTag,
                            "param: "
                                    + Arrays.toString((String[]) params
                                    .get(j)) + " ("
                                    + paramType + ")");
                } else if (paramType.equals("android.content.Intent")) {
                    Intent intent = (Intent) params.get(j);
                    Bundle bundle = (Bundle) intent.getExtras();
                    Log.d(logTag, "param: " + intent + " ("
                            + paramType + ")");
                    if (bundle != null) {
                        for (String key : bundle.keySet()) {
                            Log.d(logTag, "       " + key + " = \""
                                    + bundle.get(key) + "\"");
                        }
                    }
                } else if (paramType.equals("android.os.Bundle")) {
                    Bundle bundle = (Bundle) params.get(j);
                    Log.d(logTag, "param: " + bundle + " ("
                            + paramType + ")");
                    if (bundle != null) {
                        for (String key : bundle.keySet()) {
                            if (key.equals("android:viewHierarchyState")) {
                                Bundle state = bundle
                                        .getBundle(key);
                                if (state != null) {
                                    Log.d(logTag, "       " + key
                                            + " = \"" + state
                                            + "\"");
                                    for (String key2 : state
                                            .keySet()) {
                                        Log.d(logTag,
                                                "              "
                                                        + key2
                                                        + " = \""
                                                        + state.getByte(key2)
                                                        + "\"");
                                    }
                                }
                            } else {
                                Log.d(logTag, "       " + key
                                        + " = \"" + bundle.get(key)
                                        + "\"");
                            }
                        }
                    }
                } else {
                    Log.d(logTag, "param: " + params.get(j) + " ("
                            + paramType + ")");
                }
            } else {
                Log.d(logTag, "param: NaN (" + paramType + ")");
            }
            j++;
        }

		/*
		 * print rest of raw data
		 */
        int rest_len = data.length - parcel.dataPosition();
        if (rest_len != 0) {
            int[] bla = new int[rest_len];
            for (int k = parcel.dataPosition(); k < data.length; k++) {
                bla[k - parcel.dataPosition()] = (int) data[k] & 0xFF;
            }
            Log.d(logTag, Arrays.toString(bla));
        }
    }
}