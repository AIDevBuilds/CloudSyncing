package com.cloudsyncing.google;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Stack;

import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
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

import com.cloudsyncing.Lo;
import com.cloudsyncing.MainActivity;
import com.cloudsyncing.Misc;
import com.cloudsyncing.R;
import com.cloudsyncing.eXtreamFileAcceptFilter;
import com.dropbox.core.v2.files.Metadata;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

public class GoogleFragment extends Fragment implements OnClickListener, OnItemClickListener
{
	private static final int REQUEST_ACCOUNT_PICKER = 1000;
	private static final int REQUEST_AUTHORIZATION = 1001;

	private static final String PREF_ACCOUNT_NAME = "accountName";

	private static final String[] SCOPES = { DriveScopes.DRIVE_FILE };

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
			m_cloudFolderID = null;
			m_nameOnly = Misc.getDirLastPart(i_localFolder);
		}

		PathEntry(String i_localFolder, String i_cloudFolderID)
		{
			m_localFolder = i_localFolder;
			m_cloudFolderID = i_cloudFolderID;
			m_nameOnly = Misc.getDirLastPart(i_localFolder);
		}

		public String m_localFolder, m_cloudFolderID, m_nameOnly;
	}

	private List<java.io.File> m_localFiles;
	private List<File> m_googleDriveFile;

	private String m_localRootPath;
	private String m_rootFolderIdentifier;

	private int sync_i;

	private List<HashMap<String, Object>> uploadableFiles, downloadableFiles;

	private Stack<PathEntry> m_path;

	private DoForAll m_DoForAll;

	private View m_rootView;

	private ListView mLocalList, mCloudList;

	private com.google.api.services.drive.Drive mService;

	private GoogleAccountCredential mCredential;

	private ArrayList<Integer> localSelectedListIndexes, cloudSelectedListIndexes;

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

		mCredential = GoogleAccountCredential.usingOAuth2(MainActivity.m_activity, Arrays.asList(SCOPES)).setBackOff(new ExponentialBackOff());

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
		MainActivity.m_activity.setTitle("Google Drive");

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

		// mLocalList.setClickable(true);
		// mCloudList.setClickable(true);

		m_DoForAll = DoForAll.DO_FOR_ALL_ASK;

		m_localRootPath = Misc.getProjectDir();

		uploadableFiles = new ArrayList<HashMap<String, Object>>();
		downloadableFiles = new ArrayList<HashMap<String, Object>>();

		localSelectedListIndexes = new ArrayList<Integer>();
		cloudSelectedListIndexes = new ArrayList<Integer>();

		sync_i = -1;

		mLocalList.setOnItemClickListener(this);

		mCloudList.setOnItemClickListener(this);

		m_path = new Stack<GoogleFragment.PathEntry>();

		getResultsFromApi();

	}

	private void chooseAccount()
	{
		String accountName = getActivity().getPreferences(Context.MODE_PRIVATE).getString(PREF_ACCOUNT_NAME, null);

		if (accountName != null)
		{
			mCredential.setSelectedAccountName(accountName);

			getResultsFromApi();
		} else
		{
			// Start a dialog from which the user can choose an account
			startActivityForResult(mCredential.newChooseAccountIntent(), REQUEST_ACCOUNT_PICKER);
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data)
	{
		super.onActivityResult(requestCode, resultCode, data);

		switch (requestCode)
		{

		case REQUEST_ACCOUNT_PICKER:

			if (resultCode == Activity.RESULT_OK && data != null && data.getExtras() != null)
			{
				String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);

				if (accountName != null)
				{
					SharedPreferences settings = getActivity().getPreferences(Context.MODE_PRIVATE);

					SharedPreferences.Editor editor = settings.edit();

					editor.putString(PREF_ACCOUNT_NAME, accountName);
					editor.apply();

					mCredential.setSelectedAccountName(accountName);

					getResultsFromApi();
				}
			}
			break;

		case REQUEST_AUTHORIZATION:

			if (resultCode == Activity.RESULT_OK)
			{
				getResultsFromApi();
			}

			break;
		}
	}

	private void getResultsFromApi()
	{

		if (mCredential.getSelectedAccountName() == null)
		{
			chooseAccount();
		} else
		{
			final String rootFolderIdentifier = MainActivity.m_activity.getData("rootFolderID");

			if (!rootFolderIdentifier.isEmpty())
			{

				folderContentWithIdentifier(rootFolderIdentifier, m_localRootPath, new com.cloudsyncing.google.GoogleAPIFilesCallback()
				{
					@Override
					public void onSuccess(List<java.io.File> localFiles, List<File> cloudFiles, boolean isError)
					{
						m_path.add(new PathEntry(m_localRootPath, rootFolderIdentifier));

						if (isError)
						{
							// TODO Show error Dialog
							Lo.e("Error message");
						} else
						{
							updateList(localFiles, cloudFiles);
						}
					}
				});
			} else
			{
				fetchRootFiles(m_localRootPath, new com.cloudsyncing.google.GoogleAPIFilesCallback()
				{
					@Override
					public void onSuccess(List<java.io.File> localFiles, List<File> cloudFiles, boolean isError)
					{
						if (isError)
						{
							// TODO show error dialog
							Lo.e("error message");
						} else
						{
							updateList(localFiles, cloudFiles);
						}
					}
				});
			}
		}
	}

	private void updateList(List<java.io.File> localFiles, List<File> cloudFiles)
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

					String detail = file_size + " kb " + formatter.format(calendar.getTime());

					((TextView) convertView.findViewById(android.R.id.text2)).setText(detail);
				}

				long localModification = localFile.lastModified();

				long cloudModification = driveFileIdentifier(localFile.getName()).getModifiedTime().getValue();

				long result = localModification - cloudModification;

				if (result < -10.0)
				{
					((TextView) convertView.findViewById(android.R.id.text1)).setTextColor(Color.RED);
				}

				return convertView;
			};
		};

		mLocalList.setAdapter(localAdapter);

		ArrayAdapter<File> driveAdapter = new ArrayAdapter<File>(getActivity(), android.R.layout.simple_list_item_1, m_googleDriveFile)
		{
			public View getView(int position, View convertView, ViewGroup parent)
			{
				if (convertView == null)
				{
					LayoutInflater inflater = (LayoutInflater) getActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
					convertView = inflater.inflate(android.R.layout.simple_list_item_2, null);
				}

				File cloudFile = m_googleDriveFile.get(position);

				((TextView) convertView.findViewById(android.R.id.text1)).setText(cloudFile.getName());

				java.io.File localFile = new java.io.File(m_path.peek().m_localFolder + "/" + cloudFile.getName());

				if (m_googleDriveFile.get(position).getMimeType().equals("application/vnd.google-apps.folder"))
				{
					java.text.DateFormat formatter = new SimpleDateFormat("dd/MM/yyyy hh:mm:ss");

					Calendar calendar = Calendar.getInstance();
					calendar.setTimeInMillis(cloudFile.getModifiedTime().getValue());

					String detail = cloudFile.getSize() + " kb " + formatter.format(calendar.getTime());

					((TextView) convertView.findViewById(android.R.id.text2)).setText(detail);
				}

				if (localFile.exists())
				{
					long localModification = localFile.lastModified();

					long cloudModification = cloudFile.getModifiedTime().getValue();

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
		folderContentWithIdentifier(m_path.peek().m_cloudFolderID, m_path.peek().m_localFolder, new GoogleAPIFilesCallback()
		{

			@Override
			public void onSuccess(List<java.io.File> localFiles, List<File> cloudFiles, boolean isError)
			{
				updateList(localFiles, cloudFiles);

			}
		});
	}

	private void folderContentWithIdentifier(final String i_folderIdentifier, final String i_folderPath,
			final com.cloudsyncing.google.GoogleAPIFilesCallback i_callback)
	{
		new AsyncTask<Void, Void, List<File>>()
		{
			@Override
			protected List<File> doInBackground(Void... params)
			{
				List<File> files = new ArrayList<File>();

				try
				{
					FileList fileList = getGoogleApiService().files().list().setQ("'" + i_folderIdentifier + "' in parents and trashed = false")
							.setFields("kind, nextPageToken, files(id, name, kind, mimeType, modifiedTime, size, parents)").execute();

					files = fileList.getFiles();

				} catch (IOException e)
				{
					Lo.e(e.getMessage());
				}

				return files;
			}

			protected void onPostExecute(List<File> result)
			{
				List<File> cloudFiles = new ArrayList<File>();
				List<java.io.File> localFiles = new ArrayList<java.io.File>();

				for (File driveFile : result)
				{
					if (driveFile.getMimeType().equals("application/vnd.google-apps.folder"))
					{
						if (!(driveFile.getName().equals("Var") || driveFile.getName().equals("Recordings") || driveFile.getName().equals(
								"AE5PCGfiles")))
						{
							cloudFiles.add(driveFile);
						}
					} else
					{
						cloudFiles.add(driveFile);
					}
				}

				if (!i_folderPath.isEmpty())
				{
					localFiles = getlocalFiles(i_folderPath);
				}

				i_callback.onSuccess(localFiles, cloudFiles, false);
			};
		}.execute();
	}

	private void syncToCloud()
	{
		m_DoForAll = DoForAll.DO_FOR_ALL_ASK;

		syncToCloud(false, null);
	}

	private void syncToCloud(final boolean i_ignoreSelected, final GoogleAPICallback i_callback)
	{
		final ArrayList<Integer> selectedRows = new ArrayList<Integer>();

		for (int i = 0; i < localSelectedListIndexes.size(); i++)
		{
			int position = localSelectedListIndexes.get(i);

			selectedRows.add(position);
		}

		uploadableFiles.clear();
		downloadableFiles.clear();

		createCloudFolders(new GoogleAPICallback()
		{
			@Override
			public void onSuccess(boolean i_success)
			{
				List itemsToSync = new ArrayList();

				if (selectedRows.size() == 0 || i_ignoreSelected)
				{
					itemsToSync = m_localFiles;
				} else if (selectedRows.size() > 0)
				{
					for (int i = 0; i < selectedRows.size(); i++)
					{
						itemsToSync.add(m_localFiles.get(selectedRows.get(i)));
					}
				}

				// Show the progress window

				sync_i = -1;

				syncFilesWithItems(itemsToSync, m_path.peek().m_localFolder, m_path.peek().m_cloudFolderID, true, false, new GoogleAPICallback()
				{
					@Override
					public void onSuccess(boolean i_success)
					{
						// close the progress bar

						if (uploadableFiles.size() > 0)
						{
							// Show the progress bar

							// syncUploadingFiles(new GoogleAPICallback()
							// {
							//
							// @Override
							// public void onSuccess(boolean i_success)
							// {
							// updateListAfterSync();
							//
							// if (i_callback != null)
							// {
							// i_callback.onSuccess(i_success);
							// }
							//
							// }
							// });
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
		});
	}

	private void syncToLocal()
	{
		m_DoForAll = DoForAll.DO_FOR_ALL_ASK;
		syncToLocal(false, null);
	}

	private void syncToLocal(final boolean i_ignoreSelected, final GoogleAPICallback i_callback)
	{

		final ArrayList<Integer> selectedRows = new ArrayList<Integer>();

		for (int i = 0; i < cloudSelectedListIndexes.size(); i++)
		{
			int position = cloudSelectedListIndexes.get(i);

			selectedRows.add(position);
		}

		uploadableFiles.clear();
		downloadableFiles.clear();

		List itemsToSync = new ArrayList();

		if (selectedRows.size() == 0 || i_ignoreSelected)
		{
			itemsToSync = m_googleDriveFile;
		} else if (selectedRows.size() > 0)
		{
			for (int i = 0; i < selectedRows.size(); i++)
			{
				itemsToSync.add(m_googleDriveFile.get(selectedRows.get(i)));
			}
		}

		// show progress bar

		sync_i = -1;

		syncFilesWithItems(itemsToSync, m_path.peek().m_localFolder, m_path.peek().m_cloudFolderID, false, true, new GoogleAPICallback()
		{
			@Override
			public void onSuccess(boolean i_success)
			{
				// Hide the progress bar

				if (downloadableFiles.size() > 0)
				{
					// Show the progress bar

					// syncDownloadingFiles(new GoogleAPICallback()
					// {
					//
					// @Override
					// public void onSuccess(boolean i_success)
					// {
					// updateListAfterSync();
					//
					// if (i_callback != null)
					// {
					// i_callback.onSuccess(i_success);
					// }
					//
					// }
					// });
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

	private void sync()
	{
		uploadableFiles.clear();
		downloadableFiles.clear();

		syncToCloud(true, new GoogleAPICallback()
		{
			@Override
			public void onSuccess(boolean i_success)
			{
				if (i_success)
				{
					syncToLocal(true, null);
				}
			}
		});
	}

	private void createCloudFolders(final GoogleAPICallback i_callback)
	{
		if (m_path.peek().m_cloudFolderID != null)
		{
			i_callback.onSuccess(true);

			return;
		}

		String parentFolderID = m_rootFolderIdentifier;

		Iterator<PathEntry> iterator = m_path.iterator();

		while (iterator.hasNext())
		{
			if (iterator.next().m_cloudFolderID == null)
			{
				createSubFolder(iterator.next().m_nameOnly, parentFolderID, new GoogleAPIFolderCallback()
				{
					@Override
					public void onSuccess(String i_folder_id)
					{
						createCloudFolders(i_callback);

						return;
					}
				});
				return;
			} else
			{
				parentFolderID = iterator.next().m_cloudFolderID;
			}
		}

		i_callback.onSuccess(true);
	}

	public void uploadFile(final String i_parent_id, final String i_filePath, final String i_fileID, final GoogleAPICallback i_callback)
	{
		new AsyncTask<Void, Void, Boolean>()
		{
			@Override
			protected Boolean doInBackground(Void... params)
			{
				java.io.File filePath = new java.io.File(i_filePath);

				File fileMetadata = new File();

				fileMetadata.setName(filePath.getName());
				fileMetadata.setParents(Collections.singletonList(i_parent_id));
				fileMetadata.setMimeType(Misc.getMimeType(i_filePath));

				if (!i_fileID.isEmpty())
				{
					fileMetadata.setId(i_fileID);
				}

				FileContent mediaContent = new FileContent(Misc.getMimeType(i_filePath), filePath);

				try
				{
					File file = getGoogleApiService().files().create(fileMetadata, mediaContent).setFields("id, parents").execute();

					return true;
				} catch (IOException e)
				{
					e.printStackTrace();
					return false;
				}
			}

			protected void onPostExecute(Boolean result)
			{
				i_callback.onSuccess(result);
			};

		}.execute();
	}

	public void downloadFile(final String i_file_id, final String i_dest_dir, final GoogleAPICallback i_callback)
	{
		new AsyncTask<Void, Void, Void>()
		{
			@Override
			protected Void doInBackground(Void... params)
			{
				try
				{
					java.io.File file = new java.io.File(i_dest_dir);

					String parent = file.getParent();

					java.io.File parentDir = new java.io.File(parent);

					if (!parentDir.exists())
					{
						parentDir.mkdirs();
					}

					OutputStream outputStream = new FileOutputStream(i_dest_dir);

					getGoogleApiService().files().get(i_file_id).executeMediaAndDownloadTo(outputStream);

					i_callback.onSuccess(true);

				} catch (IOException exception)
				{
					i_callback.onSuccess(false);
				}
				return null;
			}

		}.execute();
	}

	private void syncFilesWithItems(final List i_initialItemsToProcess, final String i_path, final String i_identifier, final boolean i_upload,
			final boolean i_download, final GoogleAPICallback i_callback)
	{
		folderContentWithIdentifier(i_identifier, i_path, new GoogleAPIFilesCallback()
		{
			@Override
			public void onSuccess(final List<java.io.File> i_localFiles, final List<File> i_cloudFiles, boolean isError)
			{
				List<java.io.File> selectedLocalFiles = new ArrayList();
				List<File> selectedCloudFiles = new ArrayList();

				ArrayList<String> list1 = new ArrayList<String>();
				ArrayList<String> list2 = new ArrayList<String>();
				ArrayList<String> list3 = new ArrayList<String>();

				ArrayList<String> uniqueLocalFiles = new ArrayList<String>();
				final ArrayList<String> uniqueCloudFiles = new ArrayList<String>();
				final ArrayList<String> commonFiles = new ArrayList<String>();

				if (i_upload && i_download == false)
				{
					if (i_initialItemsToProcess != null)
					{
						selectedLocalFiles = i_initialItemsToProcess;
					}

					for (java.io.File file : selectedLocalFiles)
					{
						list1.add(file.getName());
						list2.add(file.getName());
					}

					commonFiles.clear();
					commonFiles.addAll(list1);

					for (File file : i_cloudFiles)
					{
						list3.add(file.getName());
					}

					commonFiles.retainAll(list3);

					list1.removeAll(list3);

					uniqueLocalFiles = new ArrayList<String>(list1);

				} else if (i_upload == false && i_download)
				{
					if (i_initialItemsToProcess != null)
					{
						selectedCloudFiles = i_initialItemsToProcess;
					}

					for (File file : selectedCloudFiles)
					{
						list1.add(file.getName());
						list2.add(file.getName());
					}

					for (java.io.File file : selectedLocalFiles)
					{
						list3.add(file.getName());
					}

					commonFiles.clear();
					commonFiles.addAll(list1);

					commonFiles.retainAll(list3);

					uniqueCloudFiles.clear();

					list2.removeAll(list3);

					uniqueCloudFiles.addAll(list2);

				} else
				{
					for (java.io.File file : i_localFiles)
					{
						list1.add(file.getName());
						list2.add(file.getName());
					}

					commonFiles.clear();
					commonFiles.addAll(list1);

					for (File file : i_cloudFiles)
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
					addUniqueLocalFilesToUploadableFiles(0, uniqueLocalFiles, i_path, i_identifier, new GoogleSyncCallback()
					{
						@Override
						public void onSuccess()
						{
							ArrayList<File> driveFiles = new ArrayList<File>();

							for (File file : i_cloudFiles)
							{
								if (uniqueCloudFiles.contains(file.getName()))
								{
									driveFiles.add(file);
								}
							}

							addUniqueCloudFilesToDownloadableFiles(0, driveFiles, i_path, new GoogleSyncCallback()
							{
								@Override
								public void onSuccess()
								{
									ArrayList<File> driveFiles2 = new ArrayList<File>();

									for (File file : i_cloudFiles)
									{
										if (commonFiles.contains(file.getName()))
										{
											driveFiles2.add(file);
										}
									}

									processCommonFiles(0, driveFiles2, i_path, i_upload, i_download, new GoogleSyncCallback()
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
					});
				} else if (i_upload)
				{
					addUniqueLocalFilesToUploadableFiles(0, uniqueLocalFiles, i_path, i_identifier, new GoogleSyncCallback()
					{
						@Override
						public void onSuccess()
						{
							ArrayList<File> driveFiles = new ArrayList<File>();

							for (File file : i_cloudFiles)
							{
								if (commonFiles.contains(file.getName()))
								{
									driveFiles.add(file);
								}
							}

							processCommonFiles(0, driveFiles, i_path, i_upload, i_download, new GoogleSyncCallback()
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
					ArrayList<File> driveFiles = new ArrayList<File>();

					for (File file : i_cloudFiles)
					{
						if (uniqueCloudFiles.contains(file.getName()))
						{
							driveFiles.add(file);
						}
					}

					addUniqueCloudFilesToDownloadableFiles(0, driveFiles, i_path, new GoogleSyncCallback()
					{
						@Override
						public void onSuccess()
						{
							ArrayList<File> driveFiles2 = new ArrayList<File>();

							for (File file : i_cloudFiles)
							{
								if (commonFiles.contains(file.getName()))
								{
									driveFiles2.add(file);
								}
							}

							processCommonFiles(0, driveFiles2, i_path, i_upload, i_download, new GoogleSyncCallback()
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

	private void addUniqueLocalFilesToUploadableFiles(int index, final ArrayList<String> i_files, final String i_path,
			final String i_folderIdentifier, final GoogleSyncCallback i_callback)
	{

		final int i[] = { index };

		if (i[0] < i_files.size())
		{
			final String fullPath = i_path + "/" + i_files.get(i[0]);

			if (Misc.isDir(fullPath))
			{
				createSubFolder(i_files.get(i[0]), i_folderIdentifier, new GoogleAPIFolderCallback()
				{
					@Override
					public void onSuccess(String i_folder_id)
					{
						ArrayList<String> array = getlocalFilesNames(fullPath);

						addUniqueLocalFilesToUploadableFiles(0, array, fullPath, i_folder_id, new GoogleSyncCallback()
						{
							@Override
							public void onSuccess()
							{
								i[0]++;

								addUniqueLocalFilesToUploadableFiles(i[0], i_files, i_path, i_folderIdentifier, i_callback);
							}
						});

					}
				});
			} else
			{
				HashMap<String, Object> map = new HashMap<String, Object>();

				map.put("fullPath", fullPath);
				map.put("fileName", i_files.get(i[0]));
				map.put("fileIdentifier", "");
				map.put("parentIdentifier", i_folderIdentifier);
				map.put("isOverride", false);

				uploadableFiles.add(map);

				i[0]++;

				addUniqueLocalFilesToUploadableFiles(i[0], i_files, i_path, i_folderIdentifier, i_callback);
			}
		} else
		{
			i_callback.onSuccess();
		}
	}

	private void addUniqueCloudFilesToDownloadableFiles(int i_index, final List<File> i_cloudFiles, final String i_path,
			final GoogleSyncCallback i_callback)
	{
		final int i[] = { i_index };

		if (i[0] < i_cloudFiles.size())
		{
			if (i_cloudFiles.get(i[0]).getMimeType().equals("application/vnd.google-apps.folder"))
			{
				folderContentWithIdentifier(i_cloudFiles.get(i[0]).getId(), "", new com.cloudsyncing.google.GoogleAPIFilesCallback()
				{
					@Override
					public void onSuccess(List<java.io.File> localFiles, List<File> cloudFiles, boolean isError)
					{
						String path = i_path + "/" + i_cloudFiles.get(i[0]).getName();

						java.io.File file = new java.io.File(path);

						file.mkdirs();

						addUniqueCloudFilesToDownloadableFiles(0, cloudFiles, path, new GoogleSyncCallback()
						{
							@Override
							public void onSuccess()
							{
								i[0]++;

								addUniqueCloudFilesToDownloadableFiles(i[0], i_cloudFiles, i_path, i_callback);
							}
						});
					}
				});
			} else
			{
				HashMap<String, Object> map = new HashMap<String, Object>();

				map.put("identifier", i_cloudFiles.get(i[0]).getId());
				map.put("localFilePath", (i_path + "/" + i_cloudFiles.get(i[0]).getName()));
				map.put("fileSize", i_cloudFiles.get(i[0]).getSize());
				map.put("isOverride", false);

				downloadableFiles.add(map);

				i[0]++;

				addUniqueCloudFilesToDownloadableFiles(i[0], i_cloudFiles, i_path, i_callback);
			}
		} else
		{
			i_callback.onSuccess();
		}
	}

	private void processCommonFiles(int i_index, final ArrayList<File> i_cloudFiles, final String i_path, final boolean i_upload,
			final boolean i_download, final GoogleSyncCallback i_callback)
	{
		final int i[] = { i_index };

		if (i[0] < i_cloudFiles.size())
		{
			if (i_cloudFiles.get(i[0]).getMimeType().equals("application/vnd.google-apps.folder"))
			{
				syncFilesWithItems(null, i_path + "/" + i_cloudFiles.get(i[0]).getName(), i_cloudFiles.get(i[0]).getId(), i_upload, i_download,
						new GoogleAPICallback()
						{
							@Override
							public void onSuccess(boolean i_success)
							{
								i[0]++;

								processCommonFiles(i[0], i_cloudFiles, i_path, i_upload, i_download, i_callback);
							}
						});
			} else
			{
				String path = i_path + "/" + i_cloudFiles.get(i[0]).getName();

				java.io.File file = new java.io.File(path);

				long localModification = file.lastModified();

				long cloudModification = i_cloudFiles.get(i[0]).getModifiedTime().getValue();

				long result = localModification - cloudModification;

				if (result < -10.0)
				{
					// Download from cloud

					HashMap<String, Object> map = new HashMap<String, Object>();

					map.put("identifier", i_cloudFiles.get(i[0]).getId());
					map.put("localFilePath", (i_path + "/" + i_cloudFiles.get(i[0]).getName()));
					map.put("fileSize", i_cloudFiles.get(i[0]).getSize());
					map.put("isOverride", true);

					downloadableFiles.add(map);

					i[0]++;

					processCommonFiles(i[0], i_cloudFiles, i_path, i_upload, i_download, i_callback);

				} else if (result > 10.0)
				{
					// upload file to google drive

					HashMap<String, Object> map = new HashMap<String, Object>();

					map.put("fullPath", path);
					map.put("fileName", i_cloudFiles.get(i[0]).getName());
					map.put("fileIdentifier", i_cloudFiles.get(i[0]).getId());
					map.put("parentIdentifier", i_cloudFiles.get(i[0]).getParents().get(0));
					map.put("isOverride", true);

					uploadableFiles.add(map);

					i[0]++;

					processCommonFiles(i[0], i_cloudFiles, i_path, i_upload, i_download, i_callback);

				} else
				{
					// Check the file size ?
					i[0]++;

					processCommonFiles(i[0], i_cloudFiles, i_path, i_upload, i_download, i_callback);
				}
			}
		} else
		{
			i_callback.onSuccess();
		}
	}

	private void syncUploadingFiles(final GoogleAPICallback i_callback)
	{
		sync_i++;

		if (sync_i < uploadableFiles.size())
		{

			final HashMap<String, Object> map = uploadableFiles.get(sync_i);

			if ((Boolean) map.get("isOverride"))
			{
				askToOverwrite(Misc.getDirLastPart((String) map.get("fullPath")), new WarningCallback()
				{
					@Override
					public void onResult()
					{
						uploadFile((String) map.get("parentIdentifier"), (String) map.get("fullPath"), (String) map.get("fileIdentifier"),
								new GoogleAPICallback()
								{
									@Override
									public void onSuccess(boolean i_success)
									{
										syncUploadingFiles(i_callback);
									}
								});

					}
				});

			} else
			{
				uploadFile((String) map.get("parentIdentifier"), (String) map.get("fullPath"), (String) map.get("fileIdentifier"),
						new GoogleAPICallback()
						{
							@Override
							public void onSuccess(boolean i_success)
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

	private void syncDownloadingFiles(final GoogleAPICallback i_callback)
	{
		sync_i++;

		if (sync_i < downloadableFiles.size())
		{

			// TODO change the progress title

			final HashMap<String, Object> map = new HashMap<String, Object>();

			if ((Boolean) map.get("isOverride"))
			{
				// TODO hide the progress bar

				askToOverwrite(Misc.getDirLastPart((String) map.get("fullPath")), new WarningCallback()
				{
					@Override
					public void onResult()
					{
						downloadFile((String) map.get("identifier"), (String) map.get("localFilePath"), new GoogleAPICallback()
						{
							@Override
							public void onSuccess(boolean i_success)
							{
								syncDownloadingFiles(i_callback);

							}
						});
					}
				});

			} else
			{
				downloadFile((String) map.get("identifier"), (String) map.get("localFilePath"), new GoogleAPICallback()
				{

					@Override
					public void onSuccess(boolean i_success)
					{
						syncDownloadingFiles(i_callback);
					}
				});
			}
		} else
		{

			sync_i = -1;
			i_callback.onSuccess(true);
		}
	}

	private String driveFileIdentifier(String i_name, List<File> i_cloudFiles)
	{
		for (File file : i_cloudFiles)
		{
			if (file.getName().equals(i_name))
			{
				return file.getId();
			}
		}

		return "";
	}

	private File driveFileIdentifier(String i_name)
	{
		for (File file : m_googleDriveFile)
		{
			if (file.getName().equals(i_name))
			{
				return file;
			}
		}

		return null;
	}

	@Override
	public void onItemClick(AdapterView<?> arg0, View arg1, final int position, long arg3)
	{
		if (arg0.getId() == R.id.localListView)
		{

			if (m_localFiles.get(position).isDirectory())
			{

				final String identifier = driveFileIdentifier(m_localFiles.get(position).getName(), m_googleDriveFile);

				m_path.push(new PathEntry(m_localFiles.get(position).getAbsolutePath(), identifier));

				folderContentWithIdentifier(identifier, m_path.peek().m_localFolder, new GoogleAPIFilesCallback()
				{
					@Override
					public void onSuccess(List<java.io.File> localFiles, List<File> cloudFiles, boolean isError)
					{
						updateList(localFiles, cloudFiles);

					}
				});

			} else
			{
				localSelectedListIndexes.add(position);
			}
		} else if (arg0.getId() == R.id.cloudListView)
		{

			if (m_googleDriveFile.get(position).getMimeType().equals("application/vnd.google-apps.folder"))
			{

				final String identifier = m_googleDriveFile.get(position).getId();

				if (!identifier.isEmpty())
				{
					String filePath = m_path.peek().m_localFolder + "/" + m_googleDriveFile.get(position).getName();

					m_path.push(new PathEntry(filePath, identifier));

					folderContentWithIdentifier(identifier, m_path.peek().m_localFolder, new GoogleAPIFilesCallback()
					{
						@Override
						public void onSuccess(List<java.io.File> localFiles, List<File> cloudFiles, boolean isError)
						{
							updateList(localFiles, cloudFiles);
						}
					});
				}
			} else
			{
				cloudSelectedListIndexes.add(position);
			}
		}
	}

	private void fetchRootFiles(final String i_folderPath, final com.cloudsyncing.google.GoogleAPIFilesCallback i_callback)
	{
		new AsyncTask<Void, Void, Boolean>()
		{
			@Override
			protected Boolean doInBackground(Void... params)
			{
				boolean hasRoot = false;

				try
				{
					FileList files = getGoogleApiService().files().list()
							.setQ("'root' in parents and trashed = false and name = '" + s_remoteFolder + "'").execute();

					for (File file : files.getFiles())
					{
						if (file.getName().equals(s_remoteFolder))
						{
							MainActivity.m_activity.saveData("rootFolderID", file.getId());

							hasRoot = true;

							break;
						} else
						{
							hasRoot = false;
						}
					}
				} catch (IOException e)
				{
					e.printStackTrace();
				}

				return hasRoot;
			}

			protected void onPostExecute(Boolean hasRoot)
			{
				if (hasRoot)
				{
					m_rootFolderIdentifier = MainActivity.m_activity.getData("rootFolderID");

					m_path.add(new PathEntry(m_localRootPath, m_rootFolderIdentifier));

					folderContentWithIdentifier(m_rootFolderIdentifier, i_folderPath, i_callback);
				} else
				{
					createRootFolder();
				}
			};
		}.execute();
	}

	private void createRootFolder()
	{
		new AsyncTask<Void, Void, Void>()
		{
			@Override
			protected Void doInBackground(Void... params)
			{
				try
				{
					File fileMetadata = new File();

					fileMetadata.setName("");
					fileMetadata.setMimeType("application/vnd.google-apps.folder");

					File file = getGoogleApiService().files().create(fileMetadata).setFields("id").execute();

					m_rootFolderIdentifier = file.getId();

				} catch (IOException e)
				{
					e.printStackTrace();
				}

				return null;
			}
		}.execute();
	}

	private void createSubFolder(final String i_name, final String i_parent, final GoogleAPIFolderCallback i_callback)
	{
		new AsyncTask<Void, Void, Void>()
		{
			@Override
			protected Void doInBackground(Void... params)
			{
				try
				{
					File fileMetadata = new File();

					fileMetadata.setName(i_name);
					fileMetadata.setMimeType("application/vnd.google-apps.folder");

					fileMetadata.setParents(Collections.singletonList(i_parent));

					File file = getGoogleApiService().files().create(fileMetadata).setFields("id").execute();

					i_callback.onSuccess(file.getId());

				} catch (IOException e)
				{
					e.printStackTrace();
					i_callback.onSuccess(null);
				}

				return null;
			}
		}.execute();
	}

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

				@Override
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
		switch (v.getId())
		{
		case R.id.syncLocal:
			syncToLocal();
			break;

		case R.id.syncCloud:

			syncToCloud();
			break;

		case R.id.sync:

			sync();

			break;

		case R.id.upDir:

			upDir();

			break;

		default:
			break;
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

		folderContentWithIdentifier(m_path.peek().m_cloudFolderID, m_path.peek().m_localFolder, new GoogleAPIFilesCallback()
		{
			@Override
			public void onSuccess(List<java.io.File> localFiles, List<File> cloudFiles, boolean isError)
			{
				updateList(localFiles, cloudFiles);
			}
		});
	}

	private com.google.api.services.drive.Drive getGoogleApiService()
	{
		if (mService == null)
		{
			HttpTransport transport = AndroidHttp.newCompatibleTransport();
			JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();

			Drive.Builder builder = new Drive.Builder(transport, jsonFactory, mCredential);

			builder.setApplicationName("Cloud Syncing");

			mService = builder.build();
		}

		return mService;
	}
}