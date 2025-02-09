package com.cloudsyncing;

import java.io.File;
import java.io.FileFilter;

public class eXtreamFileAcceptFilter implements FileFilter
{
	@Override
	public boolean accept(File pathname)
	{
		final String name = pathname.getName();

		if (name.equals("Var") || name.equals("Recordings") || name.equals("AE5PCGfiles"))
		{
			return false;
		} else
		{
			return true;
		}
	}
}
