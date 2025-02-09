package com.cloudsyncing;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Stack;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.cloudsyncing.google.WarningCallback;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.android.Auth;
import com.dropbox.core.http.OkHttp3Requestor;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.FolderMetadata;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;

public class DropboxFragment extends Fragment implements OnClickListener, OnItemClickListener
{

	private static final String s_remoteFolder = "CloudSyncing";

	private enum DoForAll
	{
		DO_FOR_ALL_ASK, DO_FOR_ALL_OVERWRITE, DO_FOR_ALL_DO_NOT_OVERWRITE
	}

	private class PathEntry
	{
		PathEntry(String i_localFolder)
		{
			m_localFolder = i_localFolder;
			m_cloudFolder = null;
			m_nameOnly = Misc.getDirLastPart(i_localFolder);
		}

		PathEntry(String i_localFolder, String i_cloudFolder)
		{
			m_localFolder = i_localFolder;
			m_cloudFolder = i_cloudFolder;
			m_nameOnly = Misc.getDirLastPart(i_localFolder);
		}

		public String m_localFolder, m_cloudFolder, m_nameOnly;
	}

	private static DbxClientV2 sDbxClient;

	private List<Metadata> m_googleDriveFile;
	private List<File> m_localFiles;

	private String m_localRootPath;

	private int sync_i;

	private List<HashMap<String, Object>> uploadableFiles, downloadableFiles;

	private Stack<PathEntry> m_path;

	private DoForAll m_DoForAll;

	private ArrayList<Integer> localSelectedListIndexes, cloudSelectedListIndexes;

	private View m_rootView;

	private ListView mLocalList, mCloudList;

	@Override
	public void onCreate(Bundle icicle)
	{
		super.onCreate(icicle);
		setHasOptionsMenu(false);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
	{
		if (m_rootView != null)
		{
			ViewGroup parent = (ViewGroup) m_rootView.getParent();

			try
			{
				parent.removeView(m_rootView);
			} catch (NullPointerException e)
			{
			}
		} else
		{
			m_rootView = inflater.inflate(R.layout.radiostationview, container, false);
		}

		return m_rootView;
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState)
	{
		super.onViewCreated(view, savedInstanceState);
		fill();
	}

	private void fill()
	{
		MainActivity.m_activity.setTitle("Dropbox");

		mLocalList = (ListView) m_rootView.findViewById(R.id.localListView);
		mCloudList = (ListView) m_rootView.findViewById(R.id.cloudListView);

		ImageView syncCloud = (ImageView) m_rootView.findViewById(R.id.syncCloud);
		ImageView syncLocal = (ImageView) m_rootView.findViewById(R.id.syncLocal);
		ImageView sync = (ImageView) m_rootView.findViewById(R.id.sync);
		ImageView upDir = (ImageView) m_rootView.findViewById(R.id.upDir);

		syncCloud.setOnClickListener(this);
		syncLocal.setOnClickListener(this);
		sync.setOnClickListener(this);
		upDir.setOnClickListener(this);

		mLocalList.setClickable(true);
		mCloudList.setClickable(true);

		mCloudList.setOnItemClickListener(this);
		mLocalList.setOnItemClickListener(this);

		m_DoForAll = DoForAll.DO_FOR_ALL_ASK;

		m_localRootPath = Misc.getProjectDir();

		// FIXME uncomment when merge with original code
		// localRootPath = localRootPath + "/Projects"

		uploadableFiles = new ArrayList<HashMap<String, Object>>();
		downloadableFiles = new ArrayList<HashMap<String, Object>>();

		localSelectedListIndexes = new ArrayList<Integer>();
		cloudSelectedListIndexes = new ArrayList<Integer>();

		sync_i = -1;

		m_path = new Stack<DropboxFragment.PathEntry>();

		if (!hasAuthenticated())
		{
			Auth.startOAuth2Authentication(MainActivity.m_activity, "v6v7menlo6uliai");
		} else
		{
			String accessToken = MainActivity.m_activity.getData("access-token");

			fetchRootFolderData(accessToken);
		}
	}

	private boolean hasAuthenticated()
	{
		String accessToken = MainActivity.m_activity.getData("access-token");

		if (accessToken == null || accessToken.isEmpty())
		{
			return false;
		} else
		{
			return true;
		}
	}

	@Override
	public void onResume()
	{
		super.onResume();

		String accessToken = MainActivity.m_activity.getData("access-token");

		if (accessToken == null || accessToken.isEmpty())
		{
			accessToken = Auth.getOAuth2Token();

			if (accessToken != null)
			{
				MainActivity.m_activity.saveData("access-token", accessToken);

				fetchRootFolderData(accessToken);
			}
		} else
		{
			fetchRootFolderData(accessToken);
		}
	}

	private void fetchRootFolderData(String accessToken)
	{
		folderContentWithPath("/" + s_remoteFolder, m_localRootPath, new LoadMetaDataCallback()
		{
			@Override
			public void onSuccess(List<File> localFiles, List<Metadata> cloudFiles, boolean isError)
			{
				m_path.push(new PathEntry(m_localRootPath, "/" + s_remoteFolder));

				if (isError)
				{
					// FIXME show error message
					Lo.e("Error");
				} else
				{
					updateList(localFiles, cloudFiles);
				}
			}
		});
	}

	private Metadata getFileMetadata(String i_name)
	{
		for (Metadata file : m_googleDriveFile)
		{
			if (file.getName().equals(i_name))
			{
				return file;
			}
		}
		return null;
	}

	private void updateList(List<File> localFiles, List<Metadata> cloudFiles)
	{
		m_googleDriveFile = cloudFiles;
		m_localFiles = localFiles;

		localSelectedListIndexes.clear();
		cloudSelectedListIndexes.clear();

		ArrayAdapter<java.io.File> localAdapter = new ArrayAdapter<java.io.File>(getActivity(), android.R.layout.simple_list_item_1, m_localFiles)
		{
			public View getView(int position, View convertView, ViewGroup parent)
			{
				if (convertView == null)
				{
					LayoutInflater inflater = (LayoutInflater) getActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);

					convertView = inflater.inflate(android.R.layout.simple_list_item_2, null);
				}

				java.io.File localFile = m_localFiles.get(position);

				((TextView) convertView.findViewById(android.R.id.text1)).setText(localFile.getName());

				if (!localFile.isDirectory())
				{
					// Dividing by 1024 converts the size from bytes to
					// kilobytes.
					int file_size = Integer.parseInt(String.valueOf(localFile.length() / 1024));

					java.text.DateFormat formatter = new SimpleDateFormat("dd/MM/yyyy hh:mm:ss");

					Calendar calendar = Calendar.getInstance();
					calendar.setTimeInMillis(localFile.lastModified());

					String detail = formatter.format(calendar.getTime());

					((TextView) convertView.findViewById(android.R.id.text2)).setText(detail);
				}

				long localModification = localFile.lastModified();

				Metadata metadata = getFileMetadata(localFile.getName());

				if (metadata instanceof FileMetadata)
				{
					long cloudModification = ((FileMetadata) metadata).getServerModified().getTime();

					long result = localModification - cloudModification;

					if (result < -10.0)
					{
						((TextView) convertView.findViewById(android.R.id.text1)).setTextColor(Color.RED);
					}
				}

				return convertView;
			};
		};

		mLocalList.setAdapter(localAdapter);

		ArrayAdapter<Metadata> driveAdapter = new ArrayAdapter<Metadata>(getActivity(), android.R.layout.simple_list_item_1, m_googleDriveFile)
		{
			public View getView(int position, View convertView, ViewGroup parent)
			{
				if (convertView == null)
				{
					LayoutInflater inflater = (LayoutInflater) getActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
					convertView = inflater.inflate(android.R.layout.simple_list_item_2, null);
				}

				Metadata cloudFile = m_googleDriveFile.get(position);

				((TextView) convertView.findViewById(android.R.id.text1)).setText(cloudFile.getName());

				java.io.File localFile = new java.io.File(m_path.peek().m_localFolder + "/" + cloudFile.getName());

				if (m_googleDriveFile.get(position) instanceof FileMetadata)
				{
					java.text.DateFormat formatter = new SimpleDateFormat("dd/MM/yyyy hh:mm:ss");

					Calendar calendar = Calendar.getInstance();
					calendar.setTimeInMillis(((FileMetadata) cloudFile).getServerModified().getTime());

					String detail = formatter.format(calendar.getTime());

					((TextView) convertView.findViewById(android.R.id.text2)).setText(detail);
				}

				if (localFile.exists())
				{
					long localModification = localFile.lastModified();

					long cloudModification = ((FileMetadata) cloudFile).getServerModified().getTime();

					long result = localModification - cloudModification;

					if (result > 10.0)
					{
						((TextView) convertView.findViewById(android.R.id.text1)).setTextColor(Color.RED);
					}
				} else
				{
					((TextView) convertView.findViewById(android.R.id.text1)).setTextColor(Color.RED);
				}

				return convertView;
			};
		};

		mCloudList.setAdapter(driveAdapter);
	}

	private void updateListAfterSync()
	{
		folderContentWithPath(m_path.peek().m_cloudFolder, m_path.peek().m_localFolder, new LoadMetaDataCallback()
		{
			@Override
			public void onSuccess(List<File> localFiles, List<Metadata> cloudFiles, boolean isError)
			{
				updateList(localFiles, cloudFiles);
			}
		});
	}

	private void folderContentWithPath(final String i_path, final String i_localPath, final LoadMetaDataCallback i_callback)
	{
		new AsyncTask<String, Void, ListFolderResult>()
		{
			private boolean isError = false;

			@Override
			protected ListFolderResult doInBackground(String... params)
			{
				try
				{
					return getClient().files().listFolder(i_path);
				} catch (Exception e)
				{
					e.printStackTrace();
					isError = true;
				}
				return null;
			}

			@Override
			protected void onPostExecute(ListFolderResult result)
			{
				super.onPostExecute(result);

				List<File> localFiles = getlocalFiles(i_localPath);

				List<Metadata> cloudFiles = new ArrayList<Metadata>();

				if (result.getEntries() != null)
				{
					for (Metadata metadata : result.getEntries())
					{
						if (metadata instanceof FolderMetadata)
						{
							if (!(metadata.getName().equals("Var") || metadata.getName().equals("Recordings") || metadata.getName().equals(
									"AE5PCGfiles")))
							{
								cloudFiles.add(metadata);
							}
						} else
						{
							cloudFiles.add(metadata);
						}
					}
				}

				i_callback.onSuccess(localFiles, cloudFiles, isError);
			}
		}.execute();

	}

	private void syncToCloud()
	{
		m_DoForAll = DoForAll.DO_FOR_ALL_ASK;

		syncToCloud(false, null);
	}

	private void syncToCloud(final boolean i_ignoreSelected, final DropboxApiCallback i_callback)
	{
		final ArrayList<Integer> selectedRows = new ArrayList<Integer>();

		for (Integer integer : localSelectedListIndexes)
		{
			selectedRows.add(integer);
		}

		uploadableFiles.clear();
		downloadableFiles.clear();

		List itemsToSync = new ArrayList();

		if (selectedRows.size() == 0 || i_ignoreSelected)
		{
			itemsToSync = m_localFiles;

		} else if (selectedRows.size() > 0)
		{
			for (Integer index : selectedRows)
			{
				itemsToSync.add(m_localFiles.get(index));
			}
		}

		sync_i = -1;

		syncFilesWithItems(itemsToSync, m_path.peek().m_cloudFolder, m_path.peek().m_localFolder, true, false, new DropboxApiCallback()
		{
			@Override
			public void onSuccess(boolean result)
			{
				if (uploadableFiles.size() > 0)
				{
					syncUploadingFiles(new DropboxApiCallback()
					{
						@Override
						public void onSuccess(boolean result)
						{
							updateListAfterSync();

							if (i_callback != null)
							{
								i_callback.onSuccess(result);
							}
						}
					});
				} else
				{
					if (i_callback != null)
					{
						i_callback.onSuccess(true);
					}
				}
			}
		});
	}

	private void syncToLocal()
	{
		m_DoForAll = DoForAll.DO_FOR_ALL_ASK;

		syncToLocal(false, null);
	}

	private void syncToLocal(final boolean i_ignoreSelected, final DropboxApiCallback i_callback)
	{
		final ArrayList<Integer> selectedRows = new ArrayList<Integer>();

		for (Integer integer : cloudSelectedListIndexes)
		{
			selectedRows.add(integer);
		}

		uploadableFiles.clear();
		downloadableFiles.clear();

		List itemsToSync = new ArrayList();

		if (selectedRows.size() == 0 || i_ignoreSelected)
		{
			itemsToSync = m_googleDriveFile;
		} else if (selectedRows.size() > 0)
		{
			for (Integer index : selectedRows)
			{
				itemsToSync.add(m_googleDriveFile.get(index));
			}
		}

		sync_i = -1;

		syncFilesWithItems(itemsToSync, m_path.peek().m_cloudFolder, m_path.peek().m_localFolder, false, true, new DropboxApiCallback()
		{
			@Override
			public void onSuccess(boolean result)
			{
				if (downloadableFiles.size() > 0)
				{
					syncDownloadingFiles(new DropboxApiCallback()
					{
						@Override
						public void onSuccess(boolean result)
						{
							updateListAfterSync();

							if (i_callback != null)
							{
								i_callback.onSuccess(result);
							}
						}
					});
				} else
				{
					if (i_callback != null)
					{
						i_callback.onSuccess(true);
					}
				}
			}
		});
	}

	private void syncButton()
	{
		uploadableFiles = new ArrayList<HashMap<String, Object>>();
		downloadableFiles = new ArrayList<HashMap<String, Object>>();

		syncToCloud(true, new DropboxApiCallback()
		{
			@Override
			public void onSuccess(boolean result)
			{
				if (result)
				{
					syncToLocal(true, null);
				}
			}
		});
	}

	private void uploadFile(final String i_fileName, final String i_destDir, final String i_parentRev, final String i_filePath,
			final UploadFileCallback i_callback)
	{
		new AsyncTask<Void, Void, Metadata>()
		{
			private boolean isError = false;

			@Override
			protected Metadata doInBackground(Void... params)
			{
				try
				{
					File file = new File(i_filePath);

					InputStream in = new FileInputStream(file);

					FileMetadata metadata = getClient().files().uploadBuilder(i_destDir + "/" + i_fileName).uploadAndFinish(in);

					return metadata;
				} catch (Exception e)
				{
					e.printStackTrace();
					i_callback.onSuccess(isError);
				}
				return null;
			}

			@Override
			protected void onPostExecute(Metadata result)
			{
				super.onPostExecute(result);

				if (result == null)
				{
					i_callback.onSuccess(false);
				} else
				{
					i_callback.onSuccess(true);
				}
			}
		}.execute();
	}

	private void downloadFile(final String i_name, final String i_dir, final String i_cloudPath, final String i_rev,
			final DropboxApiCallback i_callback)
	{
		new AsyncTask<Void, Void, File>()
		{
			@Override
			protected File doInBackground(Void... params)
			{
				try
				{
					File path = new File(i_dir);

					File file = new File(path, i_name);

					// Make sure the Downloads directory exists.
					if (!path.exists())
					{
						if (!path.mkdirs())
						{
							Lo.e("Unable to create directory: " + path);
						}
					} else if (!path.isDirectory())
					{
						Lo.e("Download path is not a directory: " + path);
						return null;
					}

					// Download the file.
					OutputStream outputStream = new FileOutputStream(file);

					// mDbxClient.files().download(metadata.getPathLower(),
					// metadata.getRev()).download(outputStream);
					FileMetadata metadata = getClient().files().download(i_cloudPath, i_rev).download(outputStream);

					return file;
				} catch (Exception e)
				{
					e.printStackTrace();
				}

				return null;
			}

			@Override
			protected void onPostExecute(File result)
			{
				super.onPostExecute(result);

				if (result == null)
				{
					i_callback.onSuccess(false);
				} else
				{
					i_callback.onSuccess(true);
				}
			}
		}.execute();
	}

	private void syncFilesWithItems(final List i_intialItemsToProcess, final String i_cloudPath, final String i_localPath, final boolean i_upload,
			final boolean i_download, final DropboxApiCallback i_callback)
	{
		folderContentWithPath("/" + i_cloudPath, i_localPath, new LoadMetaDataCallback()
		{
			@Override
			public void onSuccess(final List<File> i_localFiles, final List<Metadata> i_cloudFiles, boolean isError)
			{

				List<File> selectedLocalFiles = new ArrayList();
				List<Metadata> selectedCloudFiles = new ArrayList();

				ArrayList<String> list1 = new ArrayList<String>();
				ArrayList<String> list2 = new ArrayList<String>();
				ArrayList<String> list3 = new ArrayList<String>();

				ArrayList<String> uniqueLocalFiles = new ArrayList<String>();
				final ArrayList<String> uniqueCloudFiles = new ArrayList<String>();
				final ArrayList<String> commonFiles = new ArrayList<String>();

				if (i_upload && i_download == false)
				{
					if (i_intialItemsToProcess != null)
					{
						selectedLocalFiles = i_intialItemsToProcess;
					}

					for (File file : selectedLocalFiles)
					{
						list1.add(file.getName());
						list2.add(file.getName());
					}

					commonFiles.clear();
					commonFiles.addAll(list1);

					for (Metadata file : i_cloudFiles)
					{
						list3.add(file.getName());
					}

					commonFiles.retainAll(list3);

					list1.removeAll(list3);

					uniqueLocalFiles = new ArrayList<String>(list1);

				} else if (i_upload == false && i_download)
				{
					if (i_intialItemsToProcess != null)
					{
						selectedCloudFiles = i_intialItemsToProcess;
					}

					for (Metadata file : selectedCloudFiles)
					{
						list1.add(file.getName());
						list2.add(file.getName());
					}

					for (File file : selectedLocalFiles)
					{
						list3.add(file.getName());
					}

					commonFiles.clear();
					commonFiles.addAll(list1);

					commonFiles.retainAll(list3);

					uniqueCloudFiles.clear();

					list2.removeAll(list3);

					uniqueCloudFiles.addAll(list2);

					Lo.e("unique size : " + list2);

				} else
				{
					for (File file : i_localFiles)
					{
						list1.add(file.getName());
						list2.add(file.getName());
					}

					commonFiles.clear();
					commonFiles.addAll(list1);

					for (Metadata file : i_cloudFiles)
					{
						list3.add(file.getName());
					}

					commonFiles.retainAll(list3);

					list1.removeAll(list3);
					list3.removeAll(list2);

					uniqueLocalFiles = new ArrayList<String>(list1);

					uniqueCloudFiles.clear();
					uniqueCloudFiles.addAll(list3);
				}

				if (i_upload && i_download)
				{
					addUniqueLocalFileToUploadableFiles(0, uniqueCloudFiles, i_localPath, "/" + i_cloudPath, new SyncFilesCallback()
					{
						@Override
						public void onSuccess()
						{
							ArrayList<Metadata> driveFiles = new ArrayList<Metadata>();

							for (Metadata file : i_cloudFiles)
							{
								if (commonFiles.contains(file.getName()))
								{
									driveFiles.add(file);
								}
							}

							processCommonFiles(0, driveFiles, i_localPath, i_upload, i_download, new SyncFilesCallback()
							{
								@Override
								public void onSuccess()
								{
									i_callback.onSuccess(true);
								}
							});
						}
					});
				} else if (i_upload)
				{
					addUniqueLocalFileToUploadableFiles(0, uniqueCloudFiles, i_localPath, "/" + i_cloudPath, new SyncFilesCallback()
					{
						@Override
						public void onSuccess()
						{
							ArrayList<Metadata> driveFiles = new ArrayList<Metadata>();

							for (Metadata file : i_cloudFiles)
							{
								if (commonFiles.contains(file.getName()))
								{
									driveFiles.add(file);
								}
							}

							processCommonFiles(0, driveFiles, i_localPath, i_upload, i_download, new SyncFilesCallback()
							{
								@Override
								public void onSuccess()
								{
									i_callback.onSuccess(true);
								}
							});
						}
					});
				} else if (i_download)
				{
					ArrayList<Metadata> driveFiles = new ArrayList<Metadata>();

					for (Metadata file : i_cloudFiles)
					{
						if (uniqueCloudFiles.contains(file.getName()))
						{
							driveFiles.add(file);
						}
					}

					addUniqueCloudToDownloadableFiles(0, driveFiles, i_localPath, new SyncFilesCallback()
					{
						@Override
						public void onSuccess()
						{
							ArrayList<Metadata> driveFiles = new ArrayList<Metadata>();

							for (Metadata file : i_cloudFiles)
							{
								if (commonFiles.contains(file.getName()))
								{
									driveFiles.add(file);
								}
							}

							processCommonFiles(0, driveFiles, i_localPath, i_upload, i_download, new SyncFilesCallback()
							{
								@Override
								public void onSuccess()
								{
									i_callback.onSuccess(true);
								}
							});
						}
					});
				}
			}
		});
	}

	private void addUniqueLocalFileToUploadableFiles(int j, final List<String> i_files, final String i_localPath, final String i_cloudPath,
			final SyncFilesCallback i_callback)
	{
		final int[] index = { j };

		if (index[0] < i_files.size())
		{
			final String fullPath = i_localPath + "/" + i_files.get(index[0]);
			final String fullCloudPath = i_cloudPath + "/" + i_files.get(index[0]);

			if (Misc.isDir(fullPath))
			{
				List<String> array = getlocalFilesNames(fullPath);

				addUniqueLocalFileToUploadableFiles(0, array, fullPath, fullCloudPath, new SyncFilesCallback()
				{
					@Override
					public void onSuccess()
					{
						index[0]++;

						addUniqueLocalFileToUploadableFiles(index[0], i_files, i_localPath, i_cloudPath, i_callback);
					}
				});

			} else
			{
				HashMap<String, Object> hashMap = new HashMap<String, Object>();

				hashMap.put("fullPath", fullPath);
				hashMap.put("fileName", i_files.get(index[0]));
				hashMap.put("destDir", i_cloudPath);
				hashMap.put("isOverride", false);

				uploadableFiles.add(hashMap);

				index[0]++;

				addUniqueLocalFileToUploadableFiles(index[0], i_files, i_localPath, i_cloudPath, i_callback);
			}
		} else
		{
			i_callback.onSuccess();
		}
	}

	private void addUniqueCloudToDownloadableFiles(int index, final List<Metadata> i_cloudFiles, final String i_path,
			final SyncFilesCallback i_callback)
	{
		final int i[] = { index };

		if (i[0] < i_cloudFiles.size())
		{
			if (i_cloudFiles.get(i[0]) instanceof FolderMetadata)
			{
				folderContentWithPath(i_cloudFiles.get(i[0]).getPathDisplay(), i_path + "/" + i_cloudFiles.get(i[0]).getName(),
						new LoadMetaDataCallback()
						{
							@Override
							public void onSuccess(List<File> localFiles, List<Metadata> cloudFiles, boolean isError)
							{
								String path = i_path + "/" + i_cloudFiles.get(i[0]).getName();

								File file = new File(path);

								file.mkdirs();

								addUniqueCloudToDownloadableFiles(0, cloudFiles, path, new SyncFilesCallback()
								{
									@Override
									public void onSuccess()
									{
										i[0]++;

										addUniqueCloudToDownloadableFiles(i[0], i_cloudFiles, i_path, i_callback);
									}
								});
							}
						});

			} else
			{
				HashMap<String, Object> hashMap = new HashMap<String, Object>();

				hashMap.put("fileIdentifier", ((FileMetadata) i_cloudFiles.get(i[0])).getRev());
				hashMap.put("name", i_cloudFiles.get(i[0]).getName());
				hashMap.put("localDir", i_path);
				hashMap.put("cloudPath", i_cloudFiles.get(i[0]).getPathDisplay());
				hashMap.put("isOverride", false);

				downloadableFiles.add(hashMap);

				i[0]++;

				addUniqueCloudToDownloadableFiles(i[0], i_cloudFiles, i_path, i_callback);
			}
		} else
		{
			i_callback.onSuccess();
		}
	}

	private void processCommonFiles(int index, final List<Metadata> i_cloudFiles, final String i_path, final boolean i_upload,
			final boolean i_download, final SyncFilesCallback i_callback)
	{
		final int i[] = { index };

		if (i[0] < i_cloudFiles.size())
		{
			if ((i_cloudFiles.get(i[0])) instanceof FolderMetadata)
			{
				syncFilesWithItems(null, i_cloudFiles.get(i[0]).getName(), i_path + "/" + i_cloudFiles.get(i[0]).getName(), i_upload, i_download,
						new DropboxApiCallback()
						{
							@Override
							public void onSuccess(boolean result)
							{
								i[0]++;

								processCommonFiles(i[0], i_cloudFiles, i_path, i_upload, i_download, i_callback);
							}
						});
			} else
			{
				String path = i_path + "/" + i_cloudFiles.get(i[0]).getName();

				File localFile = new File(path);

				long localModification = localFile.lastModified();

				long cloudModification = ((FileMetadata) i_cloudFiles.get(i[0])).getServerModified().getTime();

				if (localModification < cloudModification)
				{
					// Download from cloud

					HashMap<String, Object> hashMap = new HashMap<String, Object>();

					hashMap.put("fileIdentifier", ((FileMetadata) i_cloudFiles.get(i[0])).getRev());
					hashMap.put("name", i_cloudFiles.get(i[0]).getName());
					hashMap.put("localDir", i_path);
					hashMap.put("cloudPath", i_cloudFiles.get(i[0]).getPathDisplay());
					hashMap.put("isOverride", true);

					downloadableFiles.add(hashMap);

					i[0]++;

					processCommonFiles(i[0], i_cloudFiles, i_path, i_upload, i_download, i_callback);

				} else if (localModification > cloudModification)
				{
					HashMap<String, Object> hashMap = new HashMap<String, Object>();

					hashMap.put("fullPath", path);
					hashMap.put("fileName", i_cloudFiles.get(i[0]).getName());
					hashMap.put("destDir", i_cloudFiles.get(i[0]).getPathDisplay());
					hashMap.put("isOverride", true);

					uploadableFiles.add(hashMap);

					i[0]++;

					processCommonFiles(i[0], i_cloudFiles, i_path, i_upload, i_download, i_callback);

				} else
				{
					i[0]++;

					processCommonFiles(i[0], i_cloudFiles, i_path, i_upload, i_download, i_callback);
				}
			}
		} else
		{
			i_callback.onSuccess();
		}
	}

	private void syncUploadingFiles(final DropboxApiCallback i_callback)
	{
		sync_i++;

		// FIXME show progress bar
		if (sync_i < uploadableFiles.size())
		{
			final HashMap<String, Object> hashMap = uploadableFiles.get(sync_i);

			if ((Boolean) hashMap.get("isOverride"))
			{
				// FIXME close progress bar
				askToOverwrite((String) hashMap.get("fileName"), new WarningCallback()
				{
					@Override
					public void onResult()
					{
						uploadFile((String) hashMap.get("fileName"), (String) hashMap.get("destDir"), (String) hashMap.get("parentIdentifier"),
								(String) hashMap.get("fullPath"), new UploadFileCallback()
								{
									@Override
									public void onSuccess(boolean result)
									{
										syncUploadingFiles(i_callback);
									}
								});
					}
				});
			} else
			{
				uploadFile((String) hashMap.get("fileName"), (String) hashMap.get("destDir"), (String) hashMap.get("parentIdentifier"),
						(String) hashMap.get("fullPath"), new UploadFileCallback()
						{
							@Override
							public void onSuccess(boolean result)
							{
								syncUploadingFiles(i_callback);
							}
						});
			}
		} else
		{
			sync_i = -1;
			i_callback.onSuccess(true);
		}
	}

	private void syncDownloadingFiles(final DropboxApiCallback i_callback)
	{
		sync_i++;

		if (sync_i < downloadableFiles.size())
		{
			// FIXME show progress bar

			final HashMap<String, Object> hashMap = downloadableFiles.get(sync_i);

			if ((Boolean) hashMap.get("isOverride"))
			{
				// FIXME close the progress bar
				askToOverwrite((String) hashMap.get("name"), new WarningCallback()
				{
					@Override
					public void onResult()
					{
						downloadFile((String) hashMap.get("name"), (String) hashMap.get("localDir"), (String) hashMap.get("cloudPath"),
								(String) hashMap.get("fileIdentifier"), new DropboxApiCallback()
								{
									@Override
									public void onSuccess(boolean result)
									{
										syncDownloadingFiles(i_callback);
									}
								});
					}
				});
			} else
			{
				downloadFile((String) hashMap.get("name"), (String) hashMap.get("localDir"), (String) hashMap.get("cloudPath"),
						(String) hashMap.get("fileIdentifier"), new DropboxApiCallback()
						{
							@Override
							public void onSuccess(boolean result)
							{
								syncDownloadingFiles(i_callback);
							}
						});
			}
		} else
		{
			// FIXME close progress bar
			Lo.e("Syncing done");

			sync_i = -1;

			i_callback.onSuccess(true);
		}
	}

	// ----------------------------------------------------

	private void askToOverwrite(String i_fileName, final WarningCallback i_callback)
	{
		if (m_DoForAll == DoForAll.DO_FOR_ALL_ASK)
		{
			AlertDialog.Builder alertDialog = new AlertDialog.Builder(MainActivity.m_activity);

			alertDialog.setTitle("Warning");
			alertDialog.setMessage(i_fileName + " File already present at destination. You want to override?");

			alertDialog.setPositiveButton("YES", new DialogInterface.OnClickListener()
			{
				public void onClick(DialogInterface dialog, int which)
				{
					dialog.cancel();

					m_DoForAll = DoForAll.DO_FOR_ALL_ASK;

					i_callback.onResult();
				}
			});

			alertDialog.setNeutralButton("Do this for all", new DialogInterface.OnClickListener()
			{
				public void onClick(DialogInterface dialog, int which)
				{
					dialog.cancel();

					m_DoForAll = DoForAll.DO_FOR_ALL_OVERWRITE;

					i_callback.onResult();
				}
			});

			alertDialog.setNegativeButton("NO", new DialogInterface.OnClickListener()
			{
				public void onClick(DialogInterface dialog, int which)
				{
					dialog.cancel();

					m_DoForAll = DoForAll.DO_FOR_ALL_DO_NOT_OVERWRITE;

					i_callback.onResult();
				}
			});

			alertDialog.show();
		} else
		{
			i_callback.onResult();
		}
	}

	private ArrayList<String> getlocalFilesNames(String i_dir)
	{
		ArrayList<String> files = new ArrayList<String>();

		try
		{
			java.io.File dir = new java.io.File(i_dir);

			for (java.io.File file : dir.listFiles())
			{
				files.add(file.getName());
			}
		} catch (NullPointerException e)
		{
			Lo.e("Failed to get the local files");
		}

		return files;
	}

	@Override
	public void onClick(View v)
	{
		if (v.getId() == R.id.syncLocal)
		{
			syncToLocal();
		}

		if (v.getId() == R.id.syncCloud)
		{
			syncToCloud();
		}

		if (v.getId() == R.id.sync)
		{
			syncButton();
		}

		if (v.getId() == R.id.upDir)
		{
			upDir();
		}
	}

	private void upDir()
	{
		if (m_path.size() <= 1)
		{
		} else
		{
			m_path.pop();
		}

		folderContentWithPath(m_path.peek().m_cloudFolder, m_path.peek().m_localFolder, new LoadMetaDataCallback()
		{
			@Override
			public void onSuccess(List<File> localFiles, List<Metadata> cloudFiles, boolean isError)
			{
				updateList(localFiles, cloudFiles);
			}
		});
	}

	@Override
	public void onItemClick(AdapterView<?> parent, View view, int position, long id)
	{
		if (parent == mLocalList)
		{
			if (m_localFiles.get(position).isDirectory())
			{
				String cloudPath = m_path.peek().m_cloudFolder + "/" + m_localFiles.get(position).getName();

				m_path.push(new PathEntry(m_localFiles.get(position).getAbsolutePath(), cloudPath));

				folderContentWithPath("/" + m_path.peek().m_cloudFolder, m_path.peek().m_localFolder, new LoadMetaDataCallback()
				{
					@Override
					public void onSuccess(List<File> localFiles, List<Metadata> cloudFiles, boolean isError)
					{
						updateList(localFiles, cloudFiles);
					}
				});
			} else
			{
				localSelectedListIndexes.add(position);
			}
		}

		if (parent == mCloudList)
		{
			if (m_googleDriveFile.get(position) instanceof FolderMetadata)
			{
				String localFilePath = m_path.peek().m_localFolder + "/" + m_googleDriveFile.get(position).getName();

				String cloudFilePath = m_path.peek().m_cloudFolder + "/" + m_googleDriveFile.get(position).getName();

				m_path.push(new PathEntry(localFilePath, cloudFilePath));

				folderContentWithPath("/" + m_path.peek().m_cloudFolder, m_path.peek().m_localFolder, new LoadMetaDataCallback()
				{
					@Override
					public void onSuccess(List<File> localFiles, List<Metadata> cloudFiles, boolean isError)
					{
						updateList(localFiles, cloudFiles);
					}
				});
			} else
			{
				cloudSelectedListIndexes.add(position);
			}
		}
	}

	private ArrayList<java.io.File> getlocalFiles(String i_dir)
	{
		ArrayList<java.io.File> files = new ArrayList<java.io.File>();

		try
		{
			java.io.File dir = new java.io.File(i_dir);

			files.addAll(Arrays.asList(dir.listFiles(new eXtreamFileAcceptFilter())));
		} catch (NullPointerException e)
		{
			Lo.e("Failed to get the local files");
		}

		return files;
	}

	public static DbxClientV2 getClient()
	{
		if (sDbxClient == null)
		{
			DbxRequestConfig requestConfig = DbxRequestConfig.newBuilder("cloud syncing").withHttpRequestor(OkHttp3Requestor.INSTANCE).build();

			String accessToken = MainActivity.m_activity.getData("access-token");

			sDbxClient = new DbxClientV2(requestConfig, accessToken);

		}

		return sDbxClient;
	}
}