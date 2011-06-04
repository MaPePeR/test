
/**
 * On Watch: sailor's watchkeeping assistant.
 * <br>Copyright 2009 Ian Cameron Smith
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


package org.hermit.onwatch.service;


import java.util.LinkedList;
import java.util.Locale;

import org.hermit.onwatch.R;

import android.content.Context;
import android.media.AudioManager;
import android.media.SoundPool;
import android.speech.tts.TextToSpeech;
import android.util.Log;


/**
 * A sound service which can be used to sound various regular alarms
 * and audible alerts.  It manages alerts from both sound clips and
 * text to speech.
 */
public class SoundService
{

	// ******************************************************************** //
    // Public Classes.
    // ******************************************************************** //
	
	/**
	 * Class describing a sound to be played.
	 */
	public static final class Sound {
		
		/**
		 * Create a sound which will play an alerting sound effect then
		 * speak some text.
		 * 
		 * @param	effect		Sound effect to play as an alert.
		 * @param	text		Text to speak.
		 */
		public Sound(SoundEffect effect, String text) {
			soundEffect = effect;
			effectCount = 1;
			spokenText = text;
		}
		
		/**
		 * Create a sound which will play a given sound effect.
		 * 
		 * @param	effect		Sound effect to play.
		 * @param	text		Number of times to repeat.
		 */
		public Sound(SoundEffect effect, int count) {
			soundEffect = effect;
			effectCount = count;
			spokenText = null;
		}
		
		@Override
		public String toString() {
			return soundEffect.toString() + "x" + effectCount;
		}
		
		private final SoundEffect soundEffect;
		private final int effectCount;
		private final String spokenText;
		
		private Runnable playListener = null;
	}
	
	
    /**
     * Enum defining the repeating alarm modes.
     */
	public enum RepeatAlarmMode {
    	OFF(0, R.drawable.ic_menu_alert_off),
    	EVERY_05(5, R.drawable.ic_menu_alert_5),
    	EVERY_10(10, R.drawable.ic_menu_alert_10),
    	EVERY_15(15, R.drawable.ic_menu_alert_15);
    	
    	RepeatAlarmMode(int mins, int icon) {
    		this.minutes = mins;
    		this.icon = icon;
    	}
    	
    	public RepeatAlarmMode next() {
    		if (this == EVERY_15)
    			return OFF;
    		else
    			return VALUES[ordinal() + 1];
    	}
    	
    	private static final RepeatAlarmMode[] VALUES = values();
    	final int minutes;
    	public final int icon;
    }
    

	// ******************************************************************** //
    // Constructors.
    // ******************************************************************** //
	
	/**
	 * Create a chimer.  As a singleton we have a private constructor.
	 * 
	 * @param	context			Parent application.
	 */
	private SoundService(Context context) {
		appContext = context;

		// Get the wakeup manager for handling async processing.
		wakeupManager = WakeupManager.getInstance(appContext);
		wakeupManager.register(alarmHandler);
		
		// Get a text-to-speech instance.
        textToSpeech = new TextToSpeech(appContext, null);
        textToSpeech.setLanguage(Locale.US);
        
        // Load the sounds.
        soundPool = createSoundPool();
        
        // Create the sound queue player.
    	queuePlayer = new QueuePlayer();
    	queuePlayer.start();
	}

	
	/**
	 * Get the chimer instance, creating it if it doesn't exist.
	 * 
	 * @param	context        Parent application.
	 * @return                 The chimer instance.
	 */
	static SoundService getInstance(Context context) {
		if (chimerInstance == null)
			chimerInstance = new SoundService(context);
		
		return chimerInstance;
	}
	

	// ******************************************************************** //
	// Shutdown.
	// ******************************************************************** //

	synchronized void shutdown() {
		queuePlayer.kill();
		textToSpeech.shutdown();
	}
	
	
	// ******************************************************************** //
	// Configuration.
	// ******************************************************************** //

	/**
	 * Query whether the half-hour watch chimes are enabled.
	 * 
	 * @return					true iff the chimes are enabled.
	 */
	synchronized boolean getChimeEnable() {
    	return chimeWatch;
    }


	/**
	 * Enable or disable the half-hour watch chimes.
	 * 
	 * @param	enable			true to enable chimes, false to disable.
	 */
	synchronized void setChimeEnable(boolean enable) {
    	chimeWatch = enable;
    }

    
    /**
     * Get the current repeating alarm mode.
     * 
     * @return					The current mode.
     */
	synchronized RepeatAlarmMode getRepeatAlarm() {
    	return alarmMode;
    }
    

    /**
     * Set the repeating alarm.
     * 
     * @param	interval		Desired alarm mode.
     */
	synchronized void setRepeatAlarm(RepeatAlarmMode mode) {
    	alarmMode = mode;
    }
    

	// ******************************************************************** //
	// Sound Control.
	// ******************************************************************** //
    
	/**
	 * Play a text alert.  When it is played, the given handler
	 * will be called, if not null.
	 * 
	 * @param	text		Text of the alert.
	 * @param	handler		A Runnable to run when the alert is played;
	 * 						or null.
	 */
    synchronized void textAlert(String text, Runnable handler) {
    	queuePlayer.queue(new Sound(SoundEffect.BUZZER, text), null);
    }
    

	// ******************************************************************** //
	// Event Handling.
	// ******************************************************************** //

    private WakeupManager.WakeupClient alarmHandler =
    										new WakeupManager.WakeupClient() {
    	/**
	     * Handle a wakeup alarm.  A wake lock will be held while we are
	     * processing the alarm, allowing us to do asynchronous processing
	     * without letting the device sleep.  However, it's essential that we
	     * notify the caller by calling {@link #done()} when we're done.
    	 * 
    	 * @param	time		The actual time in ms of the alarm, which may
    	 * 						be slightly before or after the boundary it
    	 * 						was scheduled for.
    	 * @param	daySecs		The number of seconds elapsed in the local day,
    	 * 						adjusted to align to the nearest second boundary.
    	 */
    	@Override
		public void alarm(long time, int daySecs) {
    		int dayMins = daySecs / 60;
    		int hour = dayMins / 60;
    		Sound sound = null;

    		// Chime the bells on the half hours.  Otherwise, look for
    		// an alert -- we only alert if we're not chiming the half-hour.
    		if (chimeWatch && dayMins % 30 == 0) {
    			// We calculate the bells at the *start* of this half hour -
    			// 1 to 8.  Special for the dog watches -- first dog watch
    			// has 8 bells at the end, second goes 5, 6, 7, 8.
    			int bell = (dayMins / 30) % 8;
    			if (bell == 0 || (hour == 18 && bell == 4))
    				bell = 8;
    			sound = bellSounds[bell - 1];
    		} else if (alarmMode != RepeatAlarmMode.OFF &&
    										dayMins % alarmMode.minutes == 0)
    			sound = alertSound;
    		
    		// If there's a sound to play, play it; else we're done.
			Log.i(TAG, "Chime " + dayMins + " = " +
									(sound != null ? sound : "nothing"));
    		synchronized (this) {
    			if (sound != null)
    		    	queuePlayer.queue(sound, chimeComplete);
    			else
    				done();
    		}
    	}
    };

    private Runnable chimeComplete = new Runnable() {
		@Override
		public void run() {
			alarmHandler.done();
		}
    };
    
    private Sound[] bellSounds = {
    	new Sound(SoundEffect.BELL2, 1),
    	new Sound(SoundEffect.BELL2, 2),
    	new Sound(SoundEffect.BELL2, 3),
    	new Sound(SoundEffect.BELL2, 4),
    	new Sound(SoundEffect.BELL2, 5),
    	new Sound(SoundEffect.BELL2, 6),
    	new Sound(SoundEffect.BELL2, 7),
    	new Sound(SoundEffect.BELL2, 8),
    };
    private Sound alertSound = new Sound(SoundEffect.RINGRING, 1);
    

	// ******************************************************************** //
	// Queue Management.
	// ******************************************************************** //

    // This class implements a thread which continuously scans the queue
    // for sounds to play, and plays them.
    private class QueuePlayer extends Thread {
    	QueuePlayer() {
    		super("Sound queue");
    		soundQueue = new LinkedList<Sound>();
    		textToSpeech.setOnUtteranceCompletedListener(speechComplete);
    	}
    	
    	/**
    	 * Queue a sound for playback.  When it is played, the given handler
    	 * will be called, if not null.
    	 * 
    	 * @param	sound		Sound to queue.
		 * @param	handler		A Runnable to run when the sound is played;
		 * 						or null.
    	 */
    	void queue(Sound sound, Runnable handler) {
    		sound.playListener = handler;
    		
    		synchronized (this) {
    			Log.i(TAG, "QP add " + sound);
    			soundQueue.add(sound);
    			notify();
    		}
    	}
    	
    	/**
    	 * Stop and kill this queue player.
    	 */
    	void kill() {
    		interrupt();
    	}

    	/**
    	 * Main loop of the queue player.
    	 */
    	@Override
    	public void run() {
    		synchronized (this) {
    			try {
    				while (true) {
    	    			Log.i(TAG, "QP wait");
    					wait();
    					Sound sound = soundQueue.poll();
    					if (sound != null) {
    		    			Log.i(TAG, "QP play " + sound);
    						play(sound);
    					}
    				}
    			} catch (InterruptedException e1) {
    				// We've been killed.
	    			Log.i(TAG, "QP killed");
    			}
    		}
    	}

    	
    	/**
    	 * Play the given sound.  Doesn't return until the sound is done.
    	 * 
    	 * @param	sound		The sound to play.
    	 * @throws		InterruptedException	We were interrupted.
    	 */
    	private void play(Sound sound) throws InterruptedException {
    		// Play the sound effects.
    		if (sound.soundEffect != null) {
    			int num = sound.effectCount;
    			SoundEffect fx = sound.soundEffect;
    			while (num > 0) {
    				if (fx == SoundEffect.BELL2 && num < 2)
    					fx = SoundEffect.BELL1;
    				makeSound(fx);
    				num -= fx == SoundEffect.BELL2 ? 2 : 1;
					sleep(fx.interDelay);
    			}
    			sleep(fx.postDelay);
    		}

    		// Play any text there may be.
			if (sound.spokenText != null) {
    			synchronized (speechComplete) {
    				textToSpeech.setSpeechRate(0.8f);
    				textToSpeech.speak(sound.spokenText, TextToSpeech.QUEUE_ADD, null);
    				
    				// Block until the speech is done.
    				speechComplete.wait();
    			}
			}

			// Notify the listener, if any.
			if (sound.playListener != null)
				sound.playListener.run();
    	}
    	
    	// Speech complete handler.
    	private TextToSpeech.OnUtteranceCompletedListener speechComplete =
    						new TextToSpeech.OnUtteranceCompletedListener() {
    		@Override
    		public void onUtteranceCompleted(String arg0) {
    			synchronized (speechComplete) {
    				// Unblock the sound player.
    				speechComplete.notify();
    			}
    		}
    	};
    	
    	// The queue.
    	private LinkedList<Sound> soundQueue;
    }
    

	// ******************************************************************** //
	// Sound Playing.
	// ******************************************************************** //
    
    /**
     * Create a SoundPool containing the app's sound effects.
     */
    private SoundPool createSoundPool() {
        SoundPool pool = new SoundPool(3, AudioManager.STREAM_MUSIC, 0);
        for (SoundEffect sound : SoundEffect.values())
            sound.soundId = pool.load(appContext, sound.soundRes, 1);
        
        return pool;
    }

    
    /**
     * Make a sound.  Play it immediately.  Don't touch the queue.
     * 
     * @param	which			ID of the sound to play.
     */
    private void makeSound(SoundEffect which) {
        float vol = 1.0f;
        soundPool.play(which.soundId, vol, vol, 1, 0, 1f);
	}
	

	// ******************************************************************** //
	// Class Data.
	// ******************************************************************** //

    // Debugging tag.
	private static final String TAG = "onwatch";
 
	// The instance of the chimer; null if not created yet.
	private static SoundService chimerInstance = null;

    /**
     * The sounds that we make.
     */
	private static enum SoundEffect {
    	/** A single bell. */
    	BELL1(R.raw.bells_1, 3000, 3000),
    	
    	/** Two bells. */
    	BELL2(R.raw.bells_2, 3000, 3000),
    	
    	/** An alert sound. */
    	RINGRING(R.raw.ring_ring, 2000, 0),
    	
    	/** A serious alert sound. */
    	BUZZER(R.raw.alert_buzzer, 2000, 0),
    	
    	/** A major alert sound. */
    	DEFCON(R.raw.defcon, 2000, 0);
    	
    	private SoundEffect(int res, int inter, int post) {
    		soundRes = res;
    		interDelay = inter;
    		postDelay = post;
    	}
    	
    	// Resource ID for the sound file.
    	private final int soundRes;
    	
    	// Delay in ms between iterations of the sound.
    	private final int interDelay;
    	
    	// Delay in ms after the last iteration of the sound (added to inter).
    	private final int postDelay;
    	
    	// Sound ID for playing.
        private int soundId = 0;        
    }


	// ******************************************************************** //
	// Private Data.
	// ******************************************************************** //

	// Parent app we're running in.
	private Context appContext;
    
    // Our wakeup manager, used for alarm processing.
    private WakeupManager wakeupManager = null;

	// True if the half-hour watch chimes are enabled.
	private boolean chimeWatch = false;

    // Repeating alarm mode.
    private RepeatAlarmMode alarmMode = RepeatAlarmMode.OFF;
    
    // Sound pool used for sound effects.
    private SoundPool soundPool = null;
	
	// Ringer thread currently playing bells; null if not playing.
	private QueuePlayer queuePlayer = null;
	
	// Our text-to-speech instance.
	private TextToSpeech textToSpeech;

}
