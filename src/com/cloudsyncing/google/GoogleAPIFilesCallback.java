package com.cloudsyncing.google;

import java.util.List;

import com.google.api.services.drive.model.File;

public interface GoogleAPIFilesCallback
{
	public void onSuccess(List<java.io.File> localFiles, List<File> cloudFiles, boolean isError);
}
