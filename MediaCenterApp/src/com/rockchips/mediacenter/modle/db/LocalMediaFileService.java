package com.rockchips.mediacenter.modle.db;

import java.util.ArrayList;
import java.util.List;

import org.xutils.db.sqlite.WhereBuilder;

import com.rockchips.mediacenter.application.MediaCenterApplication;
import com.rockchips.mediacenter.bean.LocalMediaFile;
import com.rockchips.mediacenter.data.ConstData;

public class LocalMediaFileService extends AppBeanService<LocalMediaFile> {

	@Override
	public boolean isExist(LocalMediaFile object) {
		LocalMediaFile localMediaFile = getObjectById(LocalMediaFile.class, object.getFileId());
		return localMediaFile != null;
	}

	
	public void deleteFilesByDeviceId(String deviceId){
		try{
			MediaCenterApplication.mDBManager.delete(LocalMediaFile.class, WhereBuilder.b("deviceID", "=", deviceId));
		}catch (Exception e){
			
		}
	}
	
	public void deleteFilesByPhysicId(String phySicId){
		try{
			MediaCenterApplication.mDBManager.delete(LocalMediaFile.class, WhereBuilder.b("physic_dev_id", "=", phySicId));
		}catch (Exception e){
			
		}
	}
	
	/**
	 * 根据父路径获取所有媒体文件列表
	 * @param parentPath
	 * @return
	 */
	private List<LocalMediaFile> getFilesByParentPath(String parentPath){
		List<LocalMediaFile> mediaFiles = new ArrayList<LocalMediaFile>();
		try{
			mediaFiles = MediaCenterApplication.mDBManager.selector(LocalMediaFile.class)
					.where("parentPath", "=", parentPath).and("type", "!=", ConstData.MediaType.SUBTITLE).findAll();
		}catch (Exception e){
			
		}
	
		return mediaFiles;
	}
	
	/**
	 * 根据父路径和媒体文件类型获取文件列表
	 * @param parentPath
	 * @param mediaType
	 * @return
	 */
	public List<LocalMediaFile> getFilesByParentPath(String parentPath, int mediaType){
		if(mediaType == -1)
			return getFilesByParentPath(parentPath);
		List<LocalMediaFile> mediaFiles = new ArrayList<LocalMediaFile>();
		try{
			mediaFiles = MediaCenterApplication.mDBManager.selector(LocalMediaFile.class)
					.where("parentPath", "=", parentPath).and("type", "=", mediaType).findAll();
		}catch (Exception e){
			
		}
		return mediaFiles;
	
	}
	
	
	/**
	 * 根据父路径和媒体文件类型获取文件列表
	 * @param parentPath
	 * @param mediaType
	 * @return
	 */
	public List<LocalMediaFile> getFilesByParentPath(String parentPath, int mediaType, int maxCount){
		if(mediaType == -1)
			return getFilesByParentPath(parentPath);
		List<LocalMediaFile> mediaFiles = new ArrayList<LocalMediaFile>();
		try{
			mediaFiles = MediaCenterApplication.mDBManager.selector(LocalMediaFile.class)
					.where("parentPath", "=", parentPath).and("type", "=", mediaType).limit(maxCount).findAll();
		}catch (Exception e){
			
		}
		return mediaFiles;
	
	}
	
}
