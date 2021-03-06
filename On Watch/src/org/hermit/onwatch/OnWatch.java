
/**
 * On Watch: sailor's watchkeeping assistant.
 * <br>Copyright 2009 Ian Cameron Smith
 * 
 * <p>This program acts as a bridge buddy for a cruising sailor on watch.
 * It displays time and navigation data, sounds chimes and alarms, etc.
 *
 * <p>This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2
 * as published by the Free Software Foundation (see COPYING).
 * 
 * <p>This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */


package org.hermit.onwatch;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

import org.hermit.android.core.Errors;
import org.hermit.android.core.MainActivity;
import org.hermit.android.core.SplashActivity;
import org.hermit.android.notice.YesNoDialog;
import org.hermit.android.widgets.TimeZoneActivity;
import org.hermit.onwatch.provider.VesselSchema;
import org.hermit.onwatch.provider.WeatherSchema;
import org.hermit.onwatch.service.OnWatchService;
import org.hermit.onwatch.service.SoundService;
import org.hermit.onwatch.service.OnWatchService.OnWatchBinder;
import org.hermit.onwatch.service.WeatherService.WeatherState;

import android.app.ActionBar;
import android.app.AlarmManager;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.ProgressDialog;
import android.app.ActionBar.Tab;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;


/**
 * This class is the main activity for OnWatch.
 */
public class OnWatch
	extends MainActivity
{

	// ******************************************************************** //
    // Activity Lifecycle.
    // ******************************************************************** //

	/**
	 * Called when the activity is starting.  This is where most
	 * initialisation should go: calling setContentView(int) to inflate
	 * the activity's UI, etc.
	 * 
	 * You can call finish() from within this function, in which case
	 * onDestroy() will be immediately called without any of the rest of
	 * the activity lifecycle executing.
	 * 
	 * Derived classes must call through to the super class's implementation
	 * of this method.  If they do not, an exception will be thrown.
	 * 
	 * @param	icicle			If the activity is being re-initialised
	 * 							after previously being shut down then this
	 * 							Bundle contains the data it most recently
	 * 							supplied in onSaveInstanceState(Bundle).
	 * 							Note: Otherwise it is null.
	 */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        // Kick off our service, if it's not running.
        launchService();

        // Create our EULA box.
        createEulaBox(R.string.eula_title, R.string.eula_text, R.string.button_close);       

        // Set up the standard dialogs.
        setAboutInfo(R.string.about_text);
        setHomeInfo(R.string.url_homepage);
        setLicenseInfo(R.string.url_license);
        
        // Set up TTS.
        checkTtsData();
        
        // Make sure we have a vessel.
        createDefaultVessel();

        // Create the time and location models.
        locationModel = LocationModel.getInstance(this);
        timeModel = TimeModel.getInstance(this);
 
        // Create the application GUI.
        setContentView(R.layout.on_watch);

        // Set up our Action Bar for tabs.
        final ActionBar actionBar = getActionBar();
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);
        
        // Remove the activity title to make space for tabs.
        actionBar.setDisplayShowTitleEnabled(false);

        // Add the view fragments to the tab bar.
        childViews = new ArrayList<ViewFragment>();
        addChild(actionBar, new HomeFragment(), R.string.tab_location);
        addChild(actionBar, new PassageListFragment(), R.string.tab_passage);
        addChild(actionBar, new ScheduleFragment(), R.string.tab_watch);
        addChild(actionBar, new AstroFragment(), R.string.tab_astro);

		// Create a handler for tick events.
		tickHandler = new Handler() {
			@Override
			public void handleMessage(Message m) {
				long time = System.currentTimeMillis();
				timeModel.tick(time);
				locationModel.tick(time);
				for (ViewFragment v : childViews)
					v.tick(time);
			}
		};
        
        // We want the audio controls to control our sound volume.
        this.setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Restore our preferences.
        updatePreferences();
        
        // Restore our app state, if this is a restart.
        if (icicle != null)
            restoreState(icicle);

        // First time, show the splash screen.
        if (!shownSplash) {
            SplashActivity.launch(this, R.drawable.splash_screen, SPLASH_TIME);
            shownSplash = true;
        }
    }


    private void addChild(ActionBar bar, ViewFragment frag, int label) {
    	ActionBar.Tab tab = bar.newTab();
    	tab.setText(label);
    	tab.setTabListener(new WatchTabListener((Fragment) frag, label));
        bar.addTab(tab);
        childViews.add(frag);
    }


    private class WatchTabListener implements ActionBar.TabListener {
        // Called to create an instance of the listener when adding a new tab
        public WatchTabListener(Fragment fragment, int label) {
            theFragment = fragment;
            tabName = getString(label);
        }

    	@Override
        public void onTabSelected(Tab tab, FragmentTransaction ft) {
            Log.i(TAG, "TabOpened(" + tabName + ")");
            ft.add(R.id.main_view, theFragment, null);
        }

    	@Override
        public void onTabUnselected(Tab tab, FragmentTransaction ft) {
            Log.i(TAG, "TabClosed(" + tabName + ")");
            ft.remove(theFragment);
        }

    	@Override
        public void onTabReselected(Tab tab, FragmentTransaction ft) {
            // do nothing
        }

        private Fragment theFragment;
        private String tabName;
    }
    
    
    /**
     * Called after {@link #onCreate} or {@link #onStop} when the current
     * activity is now being displayed to the user.  It will
     * be followed by {@link #onRestart}.
     */
    @Override
	protected void onStart() {
        Log.i(TAG, "onStart()");
        
        super.onStart();

        // Bind to the OnWatch service.
        bindService();
    }


    /**
     * Called after onRestoreInstanceState(Bundle), onRestart(), or onPause(),
     * for your activity to start interacting with the user.  This is a good
     * place to begin animations, open exclusive-access devices (such as the
     * camera), etc.
	 * 
	 * Derived classes must call through to the super class's implementation
	 * of this method.  If they do not, an exception will be thrown.
     */
    @Override
    protected void onResume() {
        Log.i(TAG, "onResume()");

        super.onResume();

        // First time round, show the EULA.
        showFirstEula();
        
        locationModel.resume();

        // Start the 1-second tick events.
		if (ticker != null)
			ticker.kill();
		ticker = new Ticker();
    }


    /**
     * Called to retrieve per-instance state from an activity before being
     * killed so that the state can be restored in onCreate(Bundle) or
     * onRestoreInstanceState(Bundle) (the Bundle populated by this method
     * will be passed to both).
     * 
     * If called, this method will occur before onStop().  There are no
     * guarantees about whether it will occur before or after onPause().
	 * 
	 * @param	outState		A Bundle in which to place any state
	 * 							information you wish to save.
     */
    @Override
    public void onSaveInstanceState(Bundle outState) {
        Log.i(TAG, "onSaveInstanceState()");
        super.onSaveInstanceState(outState);
        
        // Save our state.
        saveState(outState);
    }

    
    /**
     * Called as part of the activity lifecycle when an activity is going
     * into the background, but has not (yet) been killed.  The counterpart
     * to onResume(). 
     * 
     * After receiving this call you will usually receive a following call
     * to onStop() (after the next activity has been resumed and displayed),
     * however in some cases there will be a direct call back to onResume()
     * without going through the stopped state. 
	 * 
	 * Derived classes must call through to the super class's implementation
	 * of this method.  If they do not, an exception will be thrown.
     */
    @Override
    protected void onPause() {
        Log.i(TAG, "onPause()");
        
        super.onPause();
        
		// Stop the tick events.
		if (ticker != null) {
			ticker.kill();
			ticker = null;
		}
		
        locationModel.pause();
    }


    /**
     * Called when you are no longer visible to the user.  You will next
     * receive either {@link #onStart}, {@link #onDestroy}, or nothing,
     * depending on later user activity.
     * 
     * <p>Note that this method may never be called, in low memory situations
     * where the system does not have enough memory to keep your activity's
     * process running after its {@link #onPause} method is called.
     */
    @Override
	protected void onStop() {
        Log.i(TAG, "onStop()");
        
        super.onStop();
        
        // Unbind from the OnWatch service (but don't stop it).
        unbindService();

        // Stop all the views.
		for (ViewFragment v : childViews)
			v.stop();
    }

    
    // ******************************************************************** //
    // Text-To-Speech Setup.
    // ******************************************************************** //

    /**
     * Check whether we have the TTS data installed.
     */
    private void checkTtsData() {
        Log.i(TAG, "checkTtsData()");
    	Intent checkIntent = new Intent();
    	checkIntent.setAction(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
    	startActivityForResult(checkIntent, new ActivityListener() {
    		@Override
    		public void onActivityResult(int resultCode, Intent data) {
                if (resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS) {
                    Log.i(TAG, "checkTtsData() => OK");
                    // Do nothing.  The sound service should set itself up
                    // when it's ready, and this should succeed.
                } else {
                	YesNoDialog yn = new YesNoDialog(OnWatch.this,
                									 R.string.tts_ok,
                									 R.string.tts_cancel);
                	yn.setOnOkListener(new YesNoDialog.OnOkListener() {
						@Override
						public void onOk() {
		                	installTtsData();
						}
                	});
                	yn.show(R.string.tts_need_data_title, R.string.tts_need_data);
                }
    		}

    		@Override
    		public void onActivityCanceled(Intent data) {
    			
    		}
    	});
    }

    
    /**
     * Install the speech data for the TTS engine.
     */
    private void installTtsData() {
        Log.e(TAG, "installTtsData()");
    	Intent installIntent = new Intent();
    	installIntent.setAction(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
    	startActivityForResult(installIntent, new ActivityListener() {
    		@Override
    		public void onActivityResult(int resultCode, Intent data) {
                Log.i(TAG, "installTtsData() => OK");
                ttsDataInstalled();
    		}

    		@Override
    		public void onActivityCanceled(Intent data) {

    		}
    	});
    }
    
    
    /**
     * TTS is ready.
     */
    private void ttsDataInstalled() {
		ttsWasSetUp = true;
		if (onWatchService != null)
			onWatchService.ttsDataInstalled();
    }
    

    // ******************************************************************** //
    // Service Communications.
    // ******************************************************************** //

    /**
     * Start the service, if it's not running.
     */
    private void launchService() {
        Intent intent = new Intent(this, OnWatchService.class);
        startService(intent);
    }
    

    /**
     * Bind to the service.
     */
    private void bindService() {
        Intent intent = new Intent(this, OnWatchService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }
    

    /**
     * Pause the service (e.g. for maintenance).
     */
    public void pauseService() {
    	onWatchService.pause();
    }
    

    /**
     * Resume the service from a pause.
     */
    public void resumeService() {
    	onWatchService.resume();
    }
    

    /**
     * Unbind from the service -- without stopping it.
     */
    private void unbindService() {
        // Unbind from the OnWatch service.
        if (onWatchService != null) {
            unbindService(mConnection);
            onWatchService = null;
        }
    }


    /**
     * Shut down the app, including the background service.
     */
    private void shutdown() {
        if (onWatchService != null) {
        	onWatchService.shutdown();
            unbindService(mConnection);
            onWatchService = null;
        }
        
    	finish();
    }
    

    /**
     * Defines callbacks for service binding, passed to bindService().
     */
    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // We've bound to OnWatchService; cast the IBinder to
        	// the right type.
            OnWatchBinder binder = (OnWatchBinder) service;
            onWatchService = binder.getService();
        	updateMenus();
        	
            // Start all the views and give them the service.
    		for (ViewFragment v : childViews)
    			v.start(onWatchService);
    		
			// If TTS is set up, tell the service.
			if (ttsWasSetUp)
				onWatchService.ttsDataInstalled();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            onWatchService = null;
            
            // Stop all the views.
    		for (ViewFragment v : childViews)
    			v.stop();
        }
        
    };


    // ******************************************************************** //
    // Save and Restore.
    // ******************************************************************** //

    /**
     * Save the application's state to the given Bundle.
     * 
     * @param   icicle          A Bundle in which the app's state should
     *                          be saved.
     */
    public void saveState(Bundle icicle) {
        icicle.putBoolean("shownSplash", shownSplash);
        
//      icicle.putInt("currentView", viewFlipper.getDisplayedChild());

        // Now save our sub-components.
        // FIXME: do so.
    }


    /**
     * Restore the application's state from the given Bundle.
     * 
     * @param   icicle          The app's saved state.
     */
    public void restoreState(Bundle icicle) {
        shownSplash = icicle.getBoolean("shownSplash");
        
//      viewFlipper.setDisplayedChild(icicle.getInt("currentView"));

        // Now restore our sub-components.
        // FIXME: do so.
    }


	// ******************************************************************** //
    // Menu and Preferences Handling.
    // ******************************************************************** //

	/**
     * Initialize the contents of the game's options menu by adding items
     * to the given menu.
     * 
     * This is only called once, the first time the options menu is displayed.
     * To update the menu every time it is displayed, see
     * onPrepareOptionsMenu(Menu).
     * 
     * When we add items to the menu, we can either supply a Runnable to
     * receive notification of selection, or we can implement the Activity's
     * onOptionsItemSelected(Menu.Item) method to handle them there.
     * 
     * @param	menu			The options menu in which we should
     * 							place our items.  We can safely hold on this
     * 							(and any items created from it), making
     * 							modifications to it as desired, until the next
     * 							time onCreateOptionsMenu() is called.
     * @return					true for the menu to be displayed; false
     * 							to suppress showing it.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
    	// We must call through to the base implementation.
    	super.onCreateOptionsMenu(menu);
    	
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        
        // Get the menu items we need to control.
        chimeMenuItem = menu.findItem(R.id.menu_chimes);
        alertsMenuItem = menu.findItem(R.id.menu_alerts);
    	
    	updateMenus();
    	
        return true;
    }


    /**
     * This hook is called whenever an item in your options menu is selected.
     * Derived classes should call through to the base class for it to
     * perform the default menu handling.  (True?)
     *
     * @param	item			The menu item that was selected.
     * @return					false to have the normal processing happen.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
    	switch (item.getItemId()) {
    	case android.R.id.home:
    		// App icon has been pressed.
    		// FIXME: do something.
    		break;
    	case R.id.menu_chimes:
    		if (onWatchService != null)
    			setChimes(!onWatchService.getChimeEnable());
    		break;
    	case R.id.menu_alerts:
    		if (onWatchService != null)
    			setAlarms(onWatchService.getRepeatAlarm().next());
    		break;
        case R.id.menu_prefs:
        	// Launch the preferences activity as a subactivity, so we
        	// know when it returns.
        	Intent pint = new Intent();
        	pint.setClass(this, Preferences.class);
        	startActivityForResult(pint, new MainActivity.ActivityListener() {
				@Override
				public void onActivityResult(int resultCode, Intent data) {
		            updatePreferences();
				}
        	});
        	break;
    	case R.id.menu_backup:
    		backupData();
        	break;
    	case R.id.menu_restore:
    		restoreData();
        	break;
    	case R.id.menu_help:
            // Launch the help activity as a subactivity.
            Intent hIntent = new Intent();
            hIntent.setClass(this, Help.class);
            startActivity(hIntent);
    		break;
    	case R.id.menu_about:
            showAbout();
     		break;
    	case R.id.menu_eula:
    	    showEula();
     		break;
        case R.id.menu_exit:
        	shutdown();
        	break;
        case R.id.menu_debug_alerts:
        	debugPlayAlerts();
        	break;
    	default:
    		return super.onOptionsItemSelected(item);
    	}
    	
    	return true;
    }


    /**
     * Read our application preferences and configure ourself appropriately.
     */
    private void updatePreferences() {
    	SharedPreferences prefs =
    					PreferenceManager.getDefaultSharedPreferences(this);

    	boolean nautTime = false;
    	try {
    		nautTime = prefs.getBoolean("nautTime", false);
    	} catch (Exception e) {
    		Log.i(TAG, "Pref: bad nautTime");
    	}
    	Log.i(TAG, "Prefs: nautTime " + nautTime);
    	timeModel.setNauticalTime(nautTime);

    	boolean debugSpace = false;
    	try {
    		debugSpace = prefs.getBoolean("debugSpace", false);
    	} catch (Exception e) {
    		Log.i(TAG, "Pref: bad debugSpace");
    	}
    	Log.i(TAG, "Prefs: debugSpace " + debugSpace);

    	boolean debugTime = false;
    	try {
    		debugTime = prefs.getBoolean("debugTime", false);
    	} catch (Exception e) {
    		Log.i(TAG, "Pref: bad debugTime");
    	}
    	Log.i(TAG, "Prefs: debugTime " + debugTime);

    	// FIXME:
//    	viewController.setDebug(debugSpace, debugTime);
    }
    
    
    private void updateMenus() {
    	if (chimeMenuItem == null || alertsMenuItem == null || onWatchService == null)
    		return;

        boolean chimeWatch = onWatchService.getChimeEnable();
		chimeMenuItem.setIcon(chimeWatch ? R.drawable.ic_menu_chimes_on :
	               						   R.drawable.ic_menu_chimes_off);

        SoundService.RepeatAlarmMode alarmMode = onWatchService.getRepeatAlarm();
    	alertsMenuItem.setIcon(alarmMode.icon);
    }

    
	// ******************************************************************** //
	// Backup and Restore.
	// ******************************************************************** //

    /**
     * Backup the app data to SD card.
     */
    private void backupData() {
    	YesNoDialog yn = new YesNoDialog(this,
    									 R.string.button_ok,
    									 R.string.button_cancel);
    	yn.setOnOkListener(new YesNoDialog.OnOkListener() {
    		@Override
    		public void onOk() {
    			BackupTask bt = new BackupTask();
    			bt.execute();
    		}
    	});
    	yn.show(R.string.backup_title, R.string.backup_text);
    }
    

    private class BackupTask extends AsyncTask<Void, Integer, Integer> {
    	@Override
    	protected void onPreExecute() {
    		// Pause the service during the backup, so it's not writing
    		// to the database.
    	    pauseService();

    		prog = new ProgressDialog(OnWatch.this);
    		prog.setIndeterminate(true);
    		prog.show();
    	}

    	@Override
    	protected Integer doInBackground(Void... nothing) {
    		//            int count = urls.length;
    		//            int totalSize = 0;
    		//            for (int i = 0; i < count; i++) {
    		//                totalSize += Downloader.downloadFile(urls[i]);
    		//                publishProgress((int) ((i / (float) count) * 100));
    		//            }
    		try {
    			VesselSchema.DB_SCHEMA.backupDb(OnWatch.this, BACKUP_DIR);
    			WeatherSchema.DB_SCHEMA.backupDb(OnWatch.this, BACKUP_DIR);
    		} catch (FileNotFoundException e) {
    			Errors.reportException(OnWatch.this, e);
    		} catch (IOException e) {
    			Errors.reportException(OnWatch.this, e);
    		}
    		return 100;
    	}

    	@Override
    	protected void onProgressUpdate(Integer... progress) {

    	}

    	@Override
    	protected void onPostExecute(Integer result) {
    		prog.hide();
    		
    		// Resume the service.
    	    resumeService();
    	}

    	private ProgressDialog prog;
    }


    /**
     * Restore the app data from SD card.
     */
    private void restoreData() {
    	YesNoDialog yn = new YesNoDialog(this,
    									 R.string.button_ok,
    									 R.string.button_cancel);
    	yn.setOnOkListener(new YesNoDialog.OnOkListener() {
    		@Override
    		public void onOk() {
    			RestoreTask rt = new RestoreTask();
    			rt.execute();
    		}
    	});
    	yn.show(R.string.restore_title, R.string.restore_text);
    }
    

    private class RestoreTask extends AsyncTask<Void, Integer, Integer> {
    	@Override
    	protected void onPreExecute() {
    		// Pause the service during the restore, so it's not writing
    		// to the database.
    	    pauseService();

    		prog = new ProgressDialog(OnWatch.this);
    		prog.setIndeterminate(true);
    		prog.show();
    	}

    	@Override
    	protected Integer doInBackground(Void... nothing) {
    		//            int count = urls.length;
    		//            int totalSize = 0;
    		//            for (int i = 0; i < count; i++) {
    		//                totalSize += Downloader.downloadFile(urls[i]);
    		//                publishProgress((int) ((i / (float) count) * 100));
    		//            }
	    	try {
	    		VesselSchema.DB_SCHEMA.restoreDb(OnWatch.this, BACKUP_DIR);
	    		WeatherSchema.DB_SCHEMA.restoreDb(OnWatch.this, BACKUP_DIR);
	    	} catch (FileNotFoundException e) {
	    		Errors.reportException(OnWatch.this, e);
			} catch (IOException e) {
				Errors.reportException(OnWatch.this, e);
			}
    		return 100;
    	}

    	@Override
    	protected void onProgressUpdate(Integer... progress) {

    	}

    	@Override
    	protected void onPostExecute(Integer result) {
    		prog.dismiss();
    		
    		// Resume the service.
    	    resumeService();
    	}

    	private ProgressDialog prog;
    }


	// ******************************************************************** //
	// Vessel Management.
	// ******************************************************************** //

    /**
     * If there are no vessels in the database, create a default one.
     * 
     * <p>TODO: This is a hack until we get real vessel management.
     */
    private void createDefaultVessel() {
    	ContentResolver contentResolver = getContentResolver();
        Cursor c = null;
        try {
            c = contentResolver.query(VesselSchema.Vessels.CONTENT_URI,
            						  VesselSchema.Vessels.PROJECTION,
            						  null, null,
            						  VesselSchema.Vessels.SORT_ORDER);
            if (c == null || !c.moveToFirst()) {
            	// There's no vessel record.  Create one, with a default
            	// watch plan.
            	WatchPlan plan = WatchPlan.valueOf(0);
            	ContentValues values = new ContentValues();
            	values.put(VesselSchema.Vessels.WATCHES, plan.toString());
            	contentResolver.insert(VesselSchema.Vessels.CONTENT_URI,
            						   values);
            }
        } finally {
            if (c != null)
            	c.close();
        }
    }
    

	// ******************************************************************** //
	// Alert Controls Handling.
	// ******************************************************************** //

    /**
     * Set the half-hourly watch chimes on or off.
     * 
     * @param	enable				Requested state.
     */
    private void setChimes(boolean enable) {
        onWatchService.setChimeEnable(enable);
        updateMenus();
    }
    

    /**
     * Set the repeating alarm on or off.
     * 
     * @param	mode				Requested repeating alarm mode.
     */
    private void setAlarms(SoundService.RepeatAlarmMode mode) {
        onWatchService.setRepeatAlarm(mode);
        updateMenus();
    }


	// ******************************************************************** //
	// Timezone Handling.
	// ******************************************************************** //

    /**
     * Ask the user to pick a new timezone.
     */
    void requestTimezone() {
    	Intent intent = new Intent();
    	intent.setClass(this, TimeZoneActivity.class);
    	intent.putExtra("addZoneId", getString(R.string.timezone_naut));
    	intent.putExtra("addZoneOff", getString(R.string.timezone_naut_off));

    	startActivityForResult(intent, new MainActivity.ActivityListener() {
			@Override
			public void onActivityResult(int resultCode, Intent data) {
	            if (resultCode == RESULT_OK)
	            	setTimezone(data);
			}
    	});
    }
    

    /**
     * The user has selected a new timezone; set it up.
     * 
     * @param	data			Data returned from the timezone picker.
     */
    private void setTimezone(Intent data) {
    	String zoneId = data.getStringExtra("zoneId");
    	Log.i(TAG, "Set timezone " + zoneId);
    	
    	// Is this nautical time?
    	boolean nautTime = zoneId.equals(getString(R.string.timezone_naut));
    	
    	// Save the nautical time preference.
    	SharedPreferences prefs =
			PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("nautTime", nautTime);
        editor.commit();

    	// Set up the time model.  If this is nautical time, the time model
        // will take care of the actual timezone; otherwise set it now.
		timeModel.setNauticalTime(nautTime);
    	if (!nautTime) {
    		timeModel.setNauticalTime(false);
    		AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
    		alarm.setTimeZone(zoneId);
    	}
    }
    

    // ******************************************************************** //
    // Service Access.
    // ******************************************************************** //

	/**
	 * Get the current weather state.
	 * 
	 * @return				Current weather state; null if not known yet.
	 */
    public WeatherState getWeatherState() {
		return onWatchService != null ? onWatchService.getWeatherState() : null;
	}
	

    // ******************************************************************** //
    // Debug.
    // ******************************************************************** //

    /**
     * Play all the alerts, for testing.
     */
	private void debugPlayAlerts() {
		if (onWatchService != null)
			onWatchService.debugPlayAlerts();
	}


    // ******************************************************************** //
    // Private Types.
    // ******************************************************************** //
    
	/**
	 * Class which generates our ticks.
	 */
	private class Ticker extends Thread {
		public Ticker() {
			super("OnWatch ticker");
			enable = true;
			start();
		}

		public void kill() {
			enable = false;
			try {
				this.join();
			} catch (InterruptedException e) {
				Log.e(TAG, "Ticker interrupted while waiting to join");
			}
		}

		@Override
		public void run() {
			while (enable) {
		    	tickHandler.sendEmptyMessage(1);
				
				// Try to sleep up to the next 1-second boundary, so we
				// tick just about on the second.
				try {
					long time = System.currentTimeMillis();
					sleep(1000 - time % 1000);
				} catch (InterruptedException e) {
					enable = false;
				}
			}
		}
		
		private boolean enable;
	}


	// ******************************************************************** //
	// Class Data.
	// ******************************************************************** //

    // Debugging tag.
	private static final String TAG = "onwatch";
	
	private static final File BACKUP_DIR = new File("/sdcard/OnWatch/backups");
	
	// Time in ms for which the splash screen is displayed.
	private static final long SPLASH_TIME = 5000;


	// ******************************************************************** //
	// Private Data.
	// ******************************************************************** //

    // Menu items for the chimes and alerts controls.
    private MenuItem chimeMenuItem = null;
    private MenuItem alertsMenuItem = null;
  
	// The views we display in our tabs.
	private ArrayList<ViewFragment> childViews;
    
    // Our OnWatch service.  null if we haven't bound to it yet.
    private OnWatchService onWatchService = null;

	// The time model we use for all our timekeeping.
	private TimeModel timeModel;

	// The location model we use for all our positioning.
	private LocationModel locationModel;

    // Timer we use to generate tick events.
    private Ticker ticker = null;
	
	// Handler for updates.  We need this to get back onto
	// our thread so we can update the GUI.
	private Handler tickHandler;

    // Log whether we showed the splash screen yet this run.
    private boolean shownSplash = false;
    
    // Flag if we have installed TTS data.  That means we should tell
    // the sound service to have another go at initializing TTS, in
    // case it failed earlier.
    private boolean ttsWasSetUp = false;

}

