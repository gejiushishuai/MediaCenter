package com.rockchips.mediacenter.service;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import momo.cn.edu.fjnu.androidutils.utils.NetWorkUtils;
import momo.cn.edu.fjnu.androidutils.utils.StorageUtils;
import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.types.UDAServiceType;
import org.fourthline.cling.registry.DefaultRegistryListener;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.support.contentdirectory.callback.Browse;
import org.fourthline.cling.support.model.BrowseFlag;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import com.rockchips.mediacenter.bean.AllFileInfo;
import com.rockchips.mediacenter.bean.Device;
import com.rockchips.mediacenter.bean.DeviceScanInfo;
import com.rockchips.mediacenter.bean.FileInfo;
import com.rockchips.mediacenter.bean.LocalDevice;
import com.rockchips.mediacenter.bean.LocalMediaFile;
import com.rockchips.mediacenter.bean.NFSInfo;
import com.rockchips.mediacenter.bean.SmbInfo;
import com.rockchips.mediacenter.data.ConstData;
import com.rockchips.mediacenter.data.ConstData.ESearchType;
import com.rockchips.mediacenter.modle.db.LocalDeviceService;
import com.rockchips.mediacenter.modle.db.LocalMediaFileService;
import com.rockchips.mediacenter.modle.db.LocalMediaFolderService;
import com.rockchips.mediacenter.modle.db.ScanDirectoryService;
import com.rockchips.mediacenter.modle.db.UpnpFileService;
import com.rockchips.mediacenter.modle.db.UpnpFolderService;
import com.rockchips.mediacenter.utils.MediaFileUtils;
import com.rockchips.mediacenter.utils.MediaUtils;
import com.rockchips.mediacenter.utils.MountUtils;
import android.R.integer;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.AsyncTask.Status;
import android.os.Process;
import android.os.storage.StorageManager;
import android.os.storage.StorageEventListener;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

/**
 * 设备监视服务，设备上下线监听
 * 
 * @author GaoFei
 * 
 */
public class DeviceMonitorService extends Service {

	public static final String TAG = "DeviceMonitorService";
	public static final int DELAY_MESSAGE_TIME = 1000;
	private MonitorBinder mBinder;
	private StorageManager mStorageManager;
	private MountListener mountListener;
	private Map<String, Boolean> mountMsgs = new HashMap<String, Boolean>();
	/**
	 * 当前正在处理的消息
	 */
	private Map<String, Boolean> mCurrProcessMountMsgs = new HashMap<String, Boolean>();
	/**
	 * 固定大小线程池服务，用于执行文件扫描
	 */
	private ThreadPoolExecutor mFileScanService;
	/**
	 * 单线程池服务，用于挂载Samba设备，NFS设备
	 */
	private ExecutorService mMountNetWorkDeviceService;
	/**
	 * 本地设备上下线处理线程池
	 */
	private ExecutorService mLocalDeviceUpDownProcessService;
	/**
	 * 单线程池服务，加载视频文件缩列图
	 */
	private ThreadPoolExecutor mVideoPreviewLoadService;
	/**
	 * 单线程池服务，加载音乐，图片，APK文件缩列图
	 */
	private ThreadPoolExecutor mOtherPreviewLoadService;
	/**
	 * 单线程池服务，网络监测处理
	 */
	private ExecutorService mNetWorkCheckService;
	/**
	 * 单线程池服务，设备挂载，卸载线程
	 */
	private ExecutorService mDeviceMountService;
	private List<NFSInfo> mNFSList;
	private List<SmbInfo> mSmbList;
	/**
	 * 网络设备挂载监听器
	 */
	private NetWorkDeviceMountReceiver mNetWorkDeviceMountReceiver;
	/**
	 * 音频，视频缩列图加载监听器
	 */
	private PreviewLoadReceiver mPreviewLoadReceiver;
	/**
	 * 获取接UPNP口服务
	 */
	private AndroidUpnpService mUpnpService;
	/**
	 * UPNP设备上下线监听器
	 */
	private UpnpRegistryListener mRegistryListener;
	/**
	 * DLNA设备监听绑定器
	 */
	private ServiceConnection mUpnpConnection;
	/**
	 * 设备上下线处理
	 */
	private MountDeviceHandler mDeviceHandler;
	
	/**
	 * 异步执行刷新所有设备
	 */
	private AsyncTask<String, Integer, Integer> mRefreshAllTask;
	/**
	 * 远程设备Map表 
	 */
	private Map<String, RemoteDevice> mRemoteDevices = Collections.synchronizedMap(new HashMap<String, RemoteDevice>());
	
	/**
	 * Upnp设备搜索器
	 */
	private AsyncTask<String, Integer, Integer> mUpnpSearchTask;
	/**
	 * 设备扫描信息匹配表
	 */
	private  Map<String, DeviceScanInfo> mDeviceScanInfos = Collections.synchronizedMap(new HashMap<String, DeviceScanInfo>());
	/**
	 * Upnp文件浏览消息处理器
	 */
	private UpnpFileBrowserHandler mUpnpFileBrowserHandler;
	/**
	 * 排序依据
	 */
	private SortCriterion[] mSortCriterions =  {new SortCriterion(true, "dc:title")};
	/**
	 * 当前文件浏览
	 */
	private FileBrowser mCurrFileBrowser;
	/**
	 * 当前目录
	 */
	private Container mCurrContainer;
	/**
	 * 上一次目录
	 */
	private Container mLastContainer;
	/**
	 * Upnp文件加载完成的回调接口
	 */
	private UpnpFileLoadCallback mUpnpFileLoadCallback;
	/**
	 * Upnp设备
	 */
	private Device mUpnpDevice;
	@Override
	public void onCreate() {
		initData();
		attachService();
		initLocalDevices();
		initEvent();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		if (mBinder == null)
			mBinder = new MonitorBinder();
		return mBinder;
	}

	@Override
	public void onDestroy() {
		LocalBroadcastManager.getInstance(this).unregisterReceiver(mNetWorkDeviceMountReceiver);
		LocalBroadcastManager.getInstance(this).unregisterReceiver(mPreviewLoadReceiver);
		if (mStorageManager != null)
			mStorageManager.unregisterListener(mountListener);
		unBindServices();
	}

	/**
	 * 初始化数据
	 */
	private void initData() {
	    mVideoPreviewLoadService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<Runnable>());
	    mOtherPreviewLoadService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<Runnable>());
	    mNetWorkCheckService = Executors.newSingleThreadExecutor();
	    mLocalDeviceUpDownProcessService = Executors.newSingleThreadExecutor();
	    mMountNetWorkDeviceService = Executors.newSingleThreadExecutor();
	    mDeviceMountService = Executors.newSingleThreadExecutor();
		mPreviewLoadReceiver = new PreviewLoadReceiver();
		mNetWorkDeviceMountReceiver = new NetWorkDeviceMountReceiver();
		mDeviceHandler = new MountDeviceHandler();
		mUpnpFileBrowserHandler = new UpnpFileBrowserHandler();
		mRegistryListener = new UpnpRegistryListener();
		mBinder = new MonitorBinder();
		mStorageManager = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
		mountListener = new MountListener();
		mFileScanService = new ThreadPoolExecutor(5, 5, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
		mUpnpConnection = new ServiceConnection() {

			@Override
			public void onServiceDisconnected(ComponentName name) {
			}

			@Override
			public void onServiceConnected(ComponentName name, IBinder service) {
				mUpnpService = (AndroidUpnpService) service;
				mUpnpService.getRegistry().addListener(mRegistryListener);
				mUpnpService.getControlPoint().search();
/*				if(mUpnpSearchTask != null && mUpnpSearchTask.getStatus() == Status.RUNNING)
					mUpnpSearchTask.cancel(true);
				mUpnpSearchTask = new AsyncTask<String, Integer, Integer>(){
					protected  Integer doInBackground(String[] params) {
						//删除相关数据
						LocalDeviceService localDeviceService = new LocalDeviceService();
						List<LocalDevice> allUpnpDevices = localDeviceService.getAllUpnpDevices();
						UpnpFolderService upnpFolderService = new UpnpFolderService();
						UpnpFileService upnpFileService = new UpnpFileService();
						if(allUpnpDevices != null && allUpnpDevices.size() > 0){
							for(LocalDevice upnpDevice : allUpnpDevices){
								synchronized (mCurrProcessMountMsgs) {
									mCurrProcessMountMsgs.put(upnpDevice.getMountPath(), false);
								}
								localDeviceService.delete(upnpDevice);
								upnpFolderService.deleteFoldersByDeviceId(upnpDevice.getDeviceID());
								upnpFileService.deleteFilesByDeviceId(upnpDevice.getDeviceID());
							}
						}
						return ConstData.TaskExecuteResult.SUCCESS;
					};
					
					protected void onPostExecute(Integer result) {
						mUpnpService.getRegistry().addListener(mRegistryListener);
						mUpnpService.getControlPoint().search();
					};
				};
				mUpnpSearchTask.execute();*/
				
			}
		};
	}

	/**
	 * 初始化监听事件
	 */
	private void initEvent() {
		mStorageManager.registerListener(mountListener);
		//注册NFS，Samba设备挂载请求
		IntentFilter netWorkDeviceMountFilter = new IntentFilter();
		netWorkDeviceMountFilter.addAction(ConstData.BroadCastMsg.NFS_MOUNT);
		netWorkDeviceMountFilter.addAction(ConstData.BroadCastMsg.SAMBA_MOUNT);
		netWorkDeviceMountFilter.addAction(ConstData.BroadCastMsg.REFRESH_NETWORK_DEVICE);
		netWorkDeviceMountFilter.addAction(ConstData.BroadCastMsg.REFRESH_ALL_DEVICES);
		netWorkDeviceMountFilter.addAction(ConstData.BroadCastMsg.CHECK_NETWORK);
		netWorkDeviceMountFilter.addAction(ConstData.BroadCastMsg.RESCAN_DEVICE);
		LocalBroadcastManager.getInstance(this).registerReceiver(mNetWorkDeviceMountReceiver, netWorkDeviceMountFilter);
		//注册预览图加载请求
		IntentFilter previewLoadFilter = new IntentFilter();
		previewLoadFilter.addAction(ConstData.BroadCastMsg.LOAD_APK_PREVIEW);
		previewLoadFilter.addAction(ConstData.BroadCastMsg.LOAD_PHOTO_PREVIEW);
		previewLoadFilter.addAction(ConstData.BroadCastMsg.LOAD_AUDIO_PREVIEW);
		previewLoadFilter.addAction(ConstData.BroadCastMsg.LOAD_VIDEO_PREVIEW);
		LocalBroadcastManager.getInstance(this).registerReceiver(mPreviewLoadReceiver, previewLoadFilter);
	}
	
	private void initLocalDevices(){
		//重新挂载SD卡，U盘
		//挂载这些设备是耗时操作，需要开启线程
		DeviceInitCheckThread deviceInitCheckThread = new DeviceInitCheckThread(this);
		mFileScanService.execute(deviceInitCheckThread);
	}
	
	
	/**
	 * 获取文件扫描服务线程池
	 * @return
	 */
	public ExecutorService getFileScanService(){
		return mFileScanService;
	}
	
	/**
	 * 获取设备，挂载卸载线程
	 * @return
	 */
	public ExecutorService getDeviceMountService(){
		return mDeviceMountService;
	}
	
	public ExecutorService getNetworkCheckService(){
		return mNetWorkCheckService;
	}
	
	/**
	 * 处理本地设备（U盘，SD卡，移动硬盘的挂载/卸载事件）
	 */
	public void processLocalDeviceMountMsg(String path, String state, int deviceType, boolean isAddNetWork){
		LocalDeviceService localDeviceService = new LocalDeviceService();
		LocalDevice device = localDeviceService.getDeviceByPath(path);
		if(device != null){
			localDeviceService.delete(device);
			//需要开启线程处理
			mLocalDeviceUpDownProcessService.execute(new LocalDeviceUpDownProcessThread(device));
		}
		
		if(state.equals(Environment.MEDIA_MOUNTED)){
			device = MediaFileUtils.getLocalDeviceFromFile(new File(path));
			localDeviceService.save(device);
		}
		
		synchronized (mountMsgs) {
			mountMsgs.put(path, state.equals(Environment.MEDIA_MOUNTED));
		}
		Message message = new Message();
		if (state.equals(Environment.MEDIA_MOUNTED)) {
			message.what = ConstData.DeviceMountState.DEVICE_UP;
			
		}else{
			message.what = ConstData.DeviceMountState.DEVICE_DOWN;
		}
		message.arg1 = deviceType;
		message.arg2 = (isAddNetWork ? 1 : 0);
		message.obj = path;
		mDeviceHandler.sendMessage(message);
	}
	
	public boolean isMounted(String path) {
		synchronized (mCurrProcessMountMsgs) {
			if(mCurrProcessMountMsgs.get(path) == null)
				return false;
			return mCurrProcessMountMsgs.get(path);
		}
		
	}

	public DeviceScanInfo getDeviceScanInfo(String deviceID){
		return mDeviceScanInfos.get(deviceID);
	}
	
	public void setDeviceScanInfo(String deviceID, DeviceScanInfo deviceScanInfo){
		mDeviceScanInfos.put(deviceID, deviceScanInfo);
	}
	
	/**
	 * 移除设备扫描信息
	 * @param deviceID
	 */
	public void removeScanDeviceInfo(String deviceID){
		mDeviceScanInfos.remove(deviceID);
	}
	
	/**
	 * 搜索UPNP设备
	 */
	public void searchUpnpDevice(){
		//Log.i(TAG, "searchUpnpService");
		//数据库操作，这里需要启动线程进行处理
		new AsyncTask<Void, Integer, Integer>() {
			@Override
			protected Integer doInBackground(Void... params) {
				//Log.i(TAG, "searchUpnpService->doInBackground");
				LocalDeviceService localDeviceService = new LocalDeviceService();
				List<LocalDevice> allUpnpDevices = localDeviceService.getAllUpnpDevices();
				UpnpFolderService upnpFolderService = new UpnpFolderService();
				UpnpFileService upnpFileService = new UpnpFileService();
				if(allUpnpDevices != null && allUpnpDevices.size() > 0){
					for(LocalDevice upnpDevice : allUpnpDevices){
						synchronized (mCurrProcessMountMsgs) {
							mCurrProcessMountMsgs.put(upnpDevice.getMountPath(), false);
						}
						localDeviceService.delete(upnpDevice);
						upnpFolderService.deleteFoldersByDeviceId(upnpDevice.getDeviceID());
						upnpFileService.deleteFilesByDeviceId(upnpDevice.getDeviceID());
					}
				}
				Message message = new Message();
				message.what = ConstData.DeviceMountState.DEVICE_DOWN;
				message.arg1 = ConstData.DeviceType.DEVICE_TYPE_DMS;
				mDeviceHandler.sendMessageDelayed(message, DELAY_MESSAGE_TIME);
				return null;
			}
			
			@Override
			protected void onPostExecute(Integer result) {
				//Log.i(TAG, "searchUpnpService->onPostExecute");
				unBindServices();
				try{
					mUpnpService.getConfiguration().shutdown();
					mUpnpService.get().shutdown();
					mUpnpService.getRegistry().shutdown();
				}catch (Exception e){
					
				}
				
				attachService();
			}
		}.execute();
		
	}
	
	/**
	 * 刷新所有设备
	 */
	public void refreshAllDevices(){
		//Log.i(TAG, "refreshAllDevices");
		//先停止正在扫描的线程,这里使用AsyncTask
		synchronized (mCurrProcessMountMsgs) {
			Set<String> processPaths = mCurrProcessMountMsgs.keySet();
			if(processPaths != null && processPaths.size() > 0){
				Iterator<String> processIterator = processPaths.iterator();
				while(processIterator.hasNext()){
					mCurrProcessMountMsgs.put(processIterator.next(), false);
				}
			}
		}
		if(mRefreshAllTask != null && mRefreshAllTask.getStatus() == Status.RUNNING)
			mRefreshAllTask.cancel(true);
		mRefreshAllTask = new AsyncTask<String, Integer, Integer>(){
			protected  Integer doInBackground(String[] params) {
				try{
					//等待2秒
					TimeUnit.SECONDS.sleep(2);
					while(mFileScanService.getActiveCount() != 0)
						TimeUnit.SECONDS.sleep(2);
					return ConstData.TaskExecuteResult.SUCCESS;
				}catch(Exception e){
					//no handle
					//Log.i(TAG, "refreshAllDevices->doInBackground:" + e);
				}
				
				return ConstData.TaskExecuteResult.FAILED;
			};
			
			@Override
			protected void onPostExecute(Integer result) {
				if(result == ConstData.TaskExecuteResult.SUCCESS){
					//Log.i(TAG, "refreshAllDevices->onPostExecute->success");
					initLocalDevices();
					searchUpnpDevice();
				}
				
			}
		};
		
		mRefreshAllTask.execute();
	}
	
	/**
	 * 移除正在处理的消息
	 * @param path
	 */
	public void removeProcessMsg(String path){
		synchronized (mCurrProcessMountMsgs) {
			mCurrProcessMountMsgs.remove(path);
		}
	}
	/**
	 * 绑定各种服务
	 */
	private void attachService() {
		Intent upnpIntent = new Intent(this, MediaUpnpService.class);
		try{
			// 绑定UPNP服务
			bindService(upnpIntent, mUpnpConnection, Service.BIND_AUTO_CREATE);
		}catch (Exception e){
			//Log.i(TAG, "attachService->exception:" + e);
		}
		
	}

	/**
	 * 解绑各种服务
	 */
	private void unBindServices() {
		if(mUpnpService != null){
			mUpnpService.getRegistry().removeListener(mRegistryListener);
		}
		unbindService(mUpnpConnection);
	}
	
	
	
	/**
	 * 删除Upnp数据
	 */
	private void deleteUpnpDatas(RemoteDevice remoteDevice){
		LocalDeviceService localDeviceService = new LocalDeviceService();
		localDeviceService.deleteDeviceByPath(remoteDevice.getIdentity().getDescriptorURL().toString());
		
		UpnpFolderService upnpFolderService = new UpnpFolderService();
		upnpFolderService.deleteFoldersByDeviceId(remoteDevice.getIdentity().getUdn().getIdentifierString());
		
		UpnpFileService upnpFileService = new UpnpFileService();
		upnpFileService.deleteFilesByDeviceId(remoteDevice.getIdentity().getUdn().getIdentifierString());
		
		mCurrProcessMountMsgs.put(remoteDevice.getIdentity().getDescriptorURL().toString(), false);
	}
	
	
	/**
	 * 获取Upnp服务
	 * @return
	 */
	public AndroidUpnpService getUpnpService(){
		return mUpnpService;
	}
	
	/**
	 * 获取远程设备表
	 * @return
	 */
	public Map<String, RemoteDevice> getRemoteDevices(){
		return mRemoteDevices;
	}
	
	/**
	 * 加载Upnp文件
	 * @param container
	 * @param callback
	 */
	public void loadUpnpFile(Container container, Device device, UpnpFileLoadCallback callback){
		mUpnpFileLoadCallback = callback;
		mUpnpDevice = device;
		Log.i(TAG, "loadUpnpFile->mRemoteDevices:" + mRemoteDevices);
		Log.i(TAG, "loadUpnpFile->remoteDevice:" + mRemoteDevices.get(device.getLocalMountPath()));
		mCurrFileBrowser = new FileBrowser(mRemoteDevices.get(device.getLocalMountPath()).findService(new UDAServiceType("ContentDirectory")), container.getId(), BrowseFlag.DIRECT_CHILDREN, "*", 0, 100000L, mSortCriterions);
		mUpnpService.getControlPoint().execute(mCurrFileBrowser);
	}
	
	/**
	 * 设备挂载，卸载监听
	 * 
	 * @author GaoFei
	 * 
	 */
	class MountListener extends StorageEventListener {

		public void onUsbMassStorageConnectionChanged(boolean connected) {
			
		}

		public void onStorageStateChanged(String path, String oldState, String newState) {
			if(newState.equals(Environment.MEDIA_MOUNTED) || newState.equals(Environment.MEDIA_UNMOUNTED)){
				Log.i(TAG, "path =" + path + "   " + "oldState=" + oldState + "   " + "newState=" + newState);
				Log.i(TAG, "currentTime:" + System.currentTimeMillis());
				//这里改为线程处理
				Bundle bundle = new Bundle();
				bundle.putString(ConstData.DeviceMountMsg.MOUNT_PATH, path);
				bundle.putInt(ConstData.DeviceMountMsg.MOUNT_TYPE, ConstData.DeviceType.DEVICE_TYPE_LOCAL);
				bundle.putInt(ConstData.DeviceMountMsg.MOUNT_STATE, newState.equals(Environment.MEDIA_MOUNTED) ? ConstData.DeviceMountState.DEVICE_UP :
					ConstData.DeviceMountState.DEVICE_DOWN);
				bundle.putBoolean(ConstData.DeviceMountMsg.IS_FROM_NETWORK, false);
				bundle.putString(ConstData.DeviceMountMsg.DEVICE_NAME, path.substring(path.lastIndexOf("/") + 1, path.length()));
				//启动一个线程进行挂载，卸载处理
				mDeviceMountService.execute(new DeviceMountThread(DeviceMonitorService.this, bundle));
			}
				
		}
	}

	public class MonitorBinder extends Binder {
		public DeviceMonitorService getMonitorService() {
			return DeviceMonitorService.this;
		}
	}

	
	/**
	 * @author GaoFei
	 * UPNP文件浏览
	 */
	
	class FileBrowser extends Browse{
		
		public FileBrowser(org.fourthline.cling.model.meta.Service service, String objectID, BrowseFlag flag,String filter, long firstResult, Long maxResults, SortCriterion[] orderBy) {
			super(service, objectID, flag, filter, firstResult, maxResults, orderBy);
		}

		@Override
		public void received(ActionInvocation actionInvocation, DIDLContent didl) {
			Log.i("FileBrowser", "FileBrowser->received");
			Message receivedMessage = new Message();
			receivedMessage.what = ConstData.UpnpFileBrowserState.RECEIVED_SUCCESS;
			receivedMessage.obj = didl;
			mUpnpFileBrowserHandler.sendMessage(receivedMessage);
			//List<Container> containers = didl.getContainers();
			//List<Item> items = didl.getItems();
		}

		@Override
		public void updateStatus(Status status) {
			Log.i("FileBrowser", "FileBrowser->updateStatus:" + status);
		}
		@Override
		public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
			Log.i("FileBrowser", "FileBrowser->failure");
			Message failedMessage = new Message();
			failedMessage.what = ConstData.UpnpFileBrowserState.RECEIVED_FAILED;
			mUpnpFileBrowserHandler.sendMessage(failedMessage);
		}
		
	}
	
	/**
	 * Upnp设备上下线监听器
	 * 
	 * @author GaoFei
	 */
	class UpnpRegistryListener extends DefaultRegistryListener {
		//LocalDeviceService localDeviceService = new LocalDeviceService();

		@Override
		public void remoteDeviceAdded(Registry registry, RemoteDevice device) {
			if(device.getType().getType().equals("MediaServer")){
				//deleteUpnpDatas(device);
				// 添加至数据库或更新数据库数据
/*				LocalDevice upnpDevice = MediaFileUtils.getLocalDeviceFromRemoteDevice(device);
				String friendName = device.getDetails().getFriendlyName();
				localDeviceService.saveOrUpdate(upnpDevice);
				mRemoteDevices.put(upnpDevice.getMountPath(), device);
				synchronized (mCurrProcessMountMsgs) {
					mCurrProcessMountMsgs.put(upnpDevice.getMountPath(), true);
					Message message = new Message();
					message.what = ConstData.DeviceMountState.DEVICE_UP;
					message.arg1 = ConstData.DeviceType.DEVICE_TYPE_DMS;
					message.obj = upnpDevice.getMountPath();
					mDeviceHandler.sendMessageDelayed(message, DELAY_MESSAGE_TIME);
					//启动扫描器
					mFileScanService.execute(new UpnpFileScanThread(device, DeviceMonitorService.this, mUpnpService));
				}*/
				Log.i(TAG, "remoteDeviceAdded->device->descriptionURL:" + device.getIdentity().getDescriptorURL().toString());
				Log.i(TAG, "remoteDeviceAdded->device->Udn:" + device.getIdentity().getUdn().getIdentifierString());
				Bundle mountBundle = new Bundle();
				mountBundle.putBoolean(ConstData.DeviceMountMsg.IS_FROM_NETWORK, false);
				mountBundle.putString(ConstData.DeviceMountMsg.MOUNT_PATH, device.getIdentity().getDescriptorURL().toString());
				mountBundle.putInt(ConstData.DeviceMountMsg.MOUNT_STATE, ConstData.DeviceMountState.DEVICE_UP);
				mountBundle.putInt(ConstData.DeviceMountMsg.MOUNT_TYPE, ConstData.DeviceType.DEVICE_TYPE_DMS);
				mountBundle.putString(ConstData.DeviceMountMsg.NETWORK_PATH, device.getIdentity().getDescriptorURL().toString());
				mountBundle.putString(ConstData.DeviceMountMsg.DEVICE_NAME, device.getDetails().getFriendlyName());
				mRemoteDevices.put(device.getIdentity().getDescriptorURL().toString(), device);
				mDeviceMountService.execute(new DeviceMountThread(DeviceMonitorService.this, mountBundle));
			}
		}

		@Override
		public void remoteDeviceRemoved(Registry registry, RemoteDevice device) {
			if(device.getType().getType().equals("MediaServer")){
				Log.i(TAG, "remoteDeviceRemoved->device->descriptionURL:" + device.getIdentity().getDescriptorURL().toString());
				Log.i(TAG, "remoteDeviceRemoved->device->Udn:" + device.getIdentity().getUdn().getIdentifierString());
				Bundle downBundle = new Bundle();
				downBundle.putBoolean(ConstData.DeviceMountMsg.IS_FROM_NETWORK, false);
				downBundle.putString(ConstData.DeviceMountMsg.MOUNT_PATH, device.getIdentity().getDescriptorURL().toString());
				downBundle.putInt(ConstData.DeviceMountMsg.MOUNT_STATE, ConstData.DeviceMountState.DEVICE_DOWN);
				downBundle.putInt(ConstData.DeviceMountMsg.MOUNT_TYPE, ConstData.DeviceType.DEVICE_TYPE_DMS);
				downBundle.putString(ConstData.DeviceMountMsg.NETWORK_PATH, device.getIdentity().getDescriptorURL().toString());
				downBundle.putString(ConstData.DeviceMountMsg.DEVICE_NAME, device.getDetails().getFriendlyName());
				mRemoteDevices.remove(device.getIdentity().getDescriptorURL().toString());
				//mRemoteDevices.remove(device.getIdentity().getDescriptorURL().toString(), device);
				mDeviceMountService.execute(new DeviceMountThread(DeviceMonitorService.this, downBundle));
				/*deleteUpnpDatas(device);
				//移除远程设备
				mRemoteDevices.remove(device.getIdentity().getDescriptorURL().toString());
				Message message = new Message();
				message.what = ConstData.DeviceMountState.DEVICE_DOWN;
				message.arg1 = ConstData.DeviceType.DEVICE_TYPE_DMS;
				message.obj = device.getIdentity().getDescriptorURL().toString();
				mDeviceHandler.sendMessageDelayed(message, DELAY_MESSAGE_TIME);*/
			}
			
		}
	}
	
	/**
	 * Upnp文件浏览处理
	 * @author GaoFei
	 *
	 */
	class UpnpFileBrowserHandler extends Handler{
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case ConstData.UpnpFileBrowserState.RECEIVED_FAILED:
				break;

			case ConstData.UpnpFileBrowserState.RECEIVED_SUCCESS:
				DIDLContent content = (DIDLContent)msg.obj;
				List<FileInfo> fileInfos = MediaFileUtils.getFileInfos(content, mUpnpDevice);
				mUpnpFileLoadCallback.onSuccess(fileInfos);
				break;
			}
		}
	}
	
	/**
	 * 设备上下线处理
	 * @author GaoFei
	 *
	 */
	class MountDeviceHandler extends Handler{
		@Override
		public void handleMessage(Message msg) {
			Intent intent = new Intent();
			intent.putExtra(ConstData.IntentKey.EXTRA_DEVICE_TYPE, msg.arg1);
			intent.putExtra(ConstData.IntentKey.EXTRA_DEVICE_PATH, (String)msg.obj);
			intent.putExtra(ConstData.IntentKey.EXTRA_IS_ADD_NETWORK_DEVICE, msg.arg2 == 1);
			switch (msg.what) {
			case ConstData.DeviceMountState.DEVICE_UP:
				intent.setAction(ConstData.BroadCastMsg.DEVICE_UP);
				//发送设备上线广播
				LocalBroadcastManager.getInstance(DeviceMonitorService.this).sendBroadcast(intent);
				break;
			case ConstData.DeviceMountState.DEVICE_DOWN:
				intent.setAction(ConstData.BroadCastMsg.DEVICE_DOWN);
				//发送设备下线广播
				LocalBroadcastManager.getInstance(DeviceMonitorService.this).sendBroadcast(intent);
				break;
			default:
				break;
			}
		}
	}

	
	/**
	 * 网络设备挂载接受请求
	 * @author GaoFei
	 *
	 */
	class NetWorkDeviceMountReceiver extends BroadcastReceiver{
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			//Log.i(TAG, "NetWorkDeviceMountReceiver->receive action:" + action);
			if(action.equals(ConstData.BroadCastMsg.NFS_MOUNT)){
				NFSInfo nfsInfo = (NFSInfo)intent.getSerializableExtra(ConstData.IntentKey.EXTRA_NFS_INFO);
				boolean isAddNetWork = intent.getBooleanExtra(ConstData.IntentKey.EXTRA_IS_ADD_NETWORK_DEVICE, false);
				Bundle nfsBundle = new Bundle();
				String localMountPath = nfsInfo.getLocalMountPath();
				String name = localMountPath.substring(localMountPath.lastIndexOf("/") + 1, localMountPath.length());
				nfsBundle.putString(ConstData.DeviceMountMsg.MOUNT_PATH, localMountPath);
				nfsBundle.putInt(ConstData.DeviceMountMsg.MOUNT_STATE, ConstData.DeviceMountState.DEVICE_UP);
				nfsBundle.putInt(ConstData.DeviceMountMsg.MOUNT_TYPE, ConstData.DeviceType.DEVICE_TYPE_NFS);
				nfsBundle.putBoolean(ConstData.DeviceMountMsg.IS_FROM_NETWORK, isAddNetWork);
				nfsBundle.putString(ConstData.DeviceMountMsg.NETWORK_PATH, nfsInfo.getNetWorkPath());
				nfsBundle.putString(ConstData.DeviceMountMsg.DEVICE_NAME, name);
				mDeviceMountService.execute(new DeviceMountThread(DeviceMonitorService.this, nfsBundle));
			}else if(action.equals(ConstData.BroadCastMsg.SAMBA_MOUNT)){
				SmbInfo smbInfo = (SmbInfo)intent.getSerializableExtra(ConstData.IntentKey.EXTRA_SAMBA_INFO);
				boolean isAddNetWork = intent.getBooleanExtra(ConstData.IntentKey.EXTRA_IS_ADD_NETWORK_DEVICE, false);
				Bundle sambaBundle = new Bundle();
				String localMountPath = smbInfo.getLocalMountPath();
				String name = localMountPath.substring(localMountPath.lastIndexOf("/") + 1, localMountPath.length());
				sambaBundle.putString(ConstData.DeviceMountMsg.MOUNT_PATH, localMountPath);
				sambaBundle.putInt(ConstData.DeviceMountMsg.MOUNT_STATE, ConstData.DeviceMountState.DEVICE_UP);
				sambaBundle.putInt(ConstData.DeviceMountMsg.MOUNT_TYPE, ConstData.DeviceType.DEVICE_TYPE_SMB);
				sambaBundle.putBoolean(ConstData.DeviceMountMsg.IS_FROM_NETWORK, isAddNetWork);
				sambaBundle.putString(ConstData.DeviceMountMsg.NETWORK_PATH, smbInfo.getNetWorkPath());
				sambaBundle.putString(ConstData.DeviceMountMsg.DEVICE_NAME, name);
				mDeviceMountService.execute(new DeviceMountThread(DeviceMonitorService.this, sambaBundle));
			}else if(action.equals(ConstData.BroadCastMsg.REFRESH_NETWORK_DEVICE)){
				//刷新网络设备
				searchUpnpDevice();
			}else if(action.equals(ConstData.BroadCastMsg.REFRESH_ALL_DEVICES)){
				//刷新所有设备
				refreshAllDevices();
			}else if(action.equals(ConstData.BroadCastMsg.CHECK_NETWORK)){
				mNetWorkCheckService.execute(new NetWorkCheckThread(DeviceMonitorService.this));
				//检测网络
				if(!NetWorkUtils.haveInternet(getApplicationContext())){
					//刷新网络设备
					searchUpnpDevice();
				}
			}else if(action.equals(ConstData.BroadCastMsg.RESCAN_DEVICE)){
				Log.i(TAG, "receive broad cast rescan device");
				//重新扫描设备
				String deviceID = intent.getStringExtra(ConstData.IntentKey.EXTRA_DEVICE_ID);
				DeviceScanInfo scanInfo = mDeviceScanInfos.get(deviceID);
				//只有设备在线，才重新扫描
				if(scanInfo != null){
					Bundle mountBundle = new Bundle();
					mountBundle.putBoolean(ConstData.DeviceMountMsg.IS_FROM_NETWORK, false);
					mountBundle.putString(ConstData.DeviceMountMsg.MOUNT_PATH, scanInfo.getMountPath());
					mountBundle.putInt(ConstData.DeviceMountMsg.MOUNT_STATE, ConstData.DeviceMountState.DEVICE_UP);
					mountBundle.putInt(ConstData.DeviceMountMsg.MOUNT_TYPE, scanInfo.getDeviceType());
					mountBundle.putString(ConstData.DeviceMountMsg.NETWORK_PATH, scanInfo.getNetWrokPath());
					mountBundle.putString(ConstData.DeviceMountMsg.DEVICE_NAME, scanInfo.getDeviceName());
					mDeviceMountService.execute(new DeviceMountThread(DeviceMonitorService.this, mountBundle));
				}
			}
		}
		
	}
	
	/**
	 * 音频，视频文件预览加载接收器
	 * @author GaoFei
	 *
	 */
	class PreviewLoadReceiver extends BroadcastReceiver{
	    public void onReceive(Context context, Intent intent) {
	        String action = intent.getAction();
	        FileInfo fileInfo = (FileInfo)intent.getSerializableExtra(ConstData.IntentKey.EXTRA_FILE_INFO);
	        if(action.equals(ConstData.BroadCastMsg.LOAD_VIDEO_PREVIEW)){
	             //将线程推入队列
	             mVideoPreviewLoadService.execute(new AVPreviewLoadThread(fileInfo, DeviceMonitorService.this));
	        }else if(action.equals(ConstData.BroadCastMsg.LOAD_AUDIO_PREVIEW)){
		         //将线程推入队列
		         mOtherPreviewLoadService.execute(new AVPreviewLoadThread(fileInfo, DeviceMonitorService.this));
	        }
	        else if(action.equals(ConstData.BroadCastMsg.LOAD_PHOTO_PREVIEW)){
		         //将线程推入队列                                            
		         mOtherPreviewLoadService.execute(new PhotoPreviewLoadThread(fileInfo, DeviceMonitorService.this));
	        }else if(action.equals(ConstData.BroadCastMsg.LOAD_APK_PREVIEW)){
	        	//将线程推入队列
	        	 mOtherPreviewLoadService.execute(new APKPreviewLoadThread(fileInfo, DeviceMonitorService.this));
	        }
	    };
	}
	
	
}
