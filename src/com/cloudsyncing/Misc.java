package com.cloudsyncing;

import java.io.File;

import android.os.Environment;
import android.webkit.MimeTypeMap;

public class Misc
{
	public static String getDirLastPart(String i_dir)
	{
		String lastPart = "";

		try
		{
			lastPart = i_dir.substring(i_dir.lastIndexOf("/") + 1);
		} catch (ArrayIndexOutOfBoundsException e)
		{
			e.printStackTrace();
		}

		return lastPart;
	}

	public static String getMimeType(String i_url)
	{

		String type = null;
		String extension = MimeTypeMap.getFileExtensionFromUrl(i_url);

		if (extension != null)
		{
			type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
		}
		return type;
	}

	public static String getProjectDir()
	{
		File extStorage = Environment.getExternalStorageDirectory();

		// FIXME need to change the path
		return extStorage.getAbsolutePath() + "/Download/CloudSyncing";
	}

	public static boolean isDir(String i_file)
	{
		File file = new File(i_file);

		return file.isDirectory();
	}
}
