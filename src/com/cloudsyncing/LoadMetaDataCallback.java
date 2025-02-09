package com.cloudsyncing;

import java.io.File;
import java.util.List;

import com.dropbox.core.v2.files.Metadata;

public interface LoadMetaDataCallback
{
	void onSuccess(List<File> localFiles, List<Metadata> cloudFiles, boolean isError);
}
