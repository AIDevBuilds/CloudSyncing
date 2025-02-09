package com.cloudsyncing;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.os.Handler;

public class MainActivity extends Activity
{
	private Fragment m_fragment = null;

	private Handler m_handler = new Handler();

	public static MainActivity m_activity = null;

	public SharedPreferences m_Preferences;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		m_Preferences = getPreferences(MODE_PRIVATE);

		m_fragment = new DropboxFragment();

		m_activity = this;

		swapContentFragment(m_fragment, "DropboxFragment", false);
	}

	public void swapContentFragment(final Fragment i_newFragment, final String tag, final boolean i_addToStack)
	{
		Runnable myRunnable = new Runnable()
		{
			public void run()
			{
				try
				{
					FragmentManager fm = getFragmentManager();

					FragmentTransaction transaction = fm.beginTransaction();

					transaction.replace(R.id.container, i_newFragment, tag);

					if (i_addToStack)
					{
						transaction.addToBackStack(null);
					}

					transaction.commit();
				} catch (Exception e)
				{
					e.printStackTrace();
				}
			}
		};

		m_handler.postDelayed(myRunnable, 1);
	}

	public void saveData(String i_key, String i_value)
	{
		Editor editor = m_Preferences.edit();
		editor.putString(i_key, i_value);
		editor.commit();
	}

	public String getData(String i_key)
	{
		return m_Preferences.getString(i_key, "");
	}

	public void setTitle(String i_title)
	{
		// getSupportActionBar().setTitle(i_title);
	}

	@Override
	public void onBackPressed()
	{
		FragmentManager fm = getFragmentManager();

		if (fm.getBackStackEntryCount() > 0)
		{
			fm.popBackStack();
		} else
		{
			super.onBackPressed();
		}
	}
}