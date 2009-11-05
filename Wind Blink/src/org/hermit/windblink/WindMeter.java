
/**
 * Wind Blink: a wind meter for Android.
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


package org.hermit.windblink;


import org.hermit.android.core.SurfaceRunner;
import org.hermit.dsp.FFTTransformer;
import org.hermit.dsp.PowerMeter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;


/**
 * The main wind meter view.  This class relies on the parent SurfaceRunner
 * class to do the bulk of the animation control.
 */
public class WindMeter
	extends SurfaceRunner
{

    // ******************************************************************** //
    // Constructor.
    // ******************************************************************** //

	/**
	 * Create a WindMeter instance.
	 * 
	 * @param	app			The application context we're running in.
	 */
    public WindMeter(Context app) {
        super(app);
        
        audioReader = new AudioReader();
        
        fourierTransformer = new FFTTransformer(FFT_BLOCK);
        
        spectrumData = new float[FFT_BLOCK / 2];
        
        statsCreate(new String[] { "µs FFT" });
        setDebugPerf(true);
    }


    // ******************************************************************** //
    // Run Control.
    // ******************************************************************** //

    /**
     * The application is starting.  Perform any initial set-up prior to
     * starting the application.  We may not have a screen size yet,
     * so this is not a good place to allocate resources which depend on
     * that.
     */
    @Override
    protected void appStart() {
    }


    /**
     * Set the screen size.  This is guaranteed to be called before
     * animStart(), but perhaps not before appStart().
     * 
     * @param   width       The new width of the surface.
     * @param   height      The new height of the surface.
     * @param   config      The pixel format of the surface.
     */
    @Override
    protected void appSize(int width, int height, Bitmap.Config config) {
        // Create the bitmap for the audio waveform display,
        // and the Canvas for drawing into it.
        waveBitmap = getBitmap(256, 64);
        waveCanvas = new Canvas(waveBitmap);
        
        // Create the bitmap for the audio spectrum display,
        // and the Canvas for drawing into it.
        spectrumBitmap = getBitmap(256, 256);
        spectrumCanvas = new Canvas(spectrumBitmap);

        // Make a Paint for drawing the meter.
        screenPaint = new Paint();
        screenPaint.setAntiAlias(true);
    }
    

    /**
     * We are starting the animation loop.  The screen size is known.
     * 
     * <p>doUpdate() and doDraw() may be called from this point on.
     */
    @Override
    protected void animStart() {
        audioReader.startReader(FFT_BLOCK * DECIMATE, new AudioReader.Listener() {
            @Override
            public void onReadComplete(short[] buffer) {
                processAudio(buffer);
            }
        });
    }
    

    /**
     * We are stopping the animation loop, for example to pause the app.
     * 
     * <p>doUpdate() and doDraw() will not be called from this point on.
     */
    @Override
    protected void animStop() {
        audioReader.stopReader();
    }
    

    /**
     * The application is closing down.  Clean up any resources.
     */
    @Override
    protected void appStop() {
    }
    

    // ******************************************************************** //
    // Audio Processing.
    // ******************************************************************** //

    /**
     * Handle audio input.
     */
    private void processAudio(short[] buffer) {
        synchronized (audioReader) {
            audioData = buffer;
            audioProcessed = false;
        }
    }
    
    
    // ******************************************************************** //
    // Animation Rendering.
    // ******************************************************************** //
    
    /**
     * Update the state of the application for the current frame.
     * 
     * <p>Applications must override this, and can use it to update
     * for example the physics of a game.  This may be a no-op in some cases.
     * 
     * <p>doDraw() will always be called after this method is called;
     * however, the converse is not true, as we sometimes need to draw
     * just to update the screen.  Hence this method is useful for
     * updates which are dependent on time rather than frames.
     * 
     * @param   now         Current time in ms.
     */
    @Override
    protected void doUpdate(long now) {
        boolean gotData = false;
        float sigPower = 0f;

        // Lock the audio input buffer only while we read it.
        synchronized (audioReader) {
            if (audioData != null && !audioProcessed) {
                final int len = audioData.length;

                // Calculate the power now, while we have the input
                // buffer; this is pretty cheap.
                sigPower = PowerMeter.calculatePowerDb(audioData, 0, len);

                // Set up the FFT input data.
                fourierTransformer.setInput(audioData, len - FFT_BLOCK, FFT_BLOCK);
                audioProcessed = true;
                gotData = true;
                
                drawWaveform(waveCanvas, now, 0, 0);
            }
        }

        if (gotData) {
            // Do the (expensive) transformation.
            // The transformer has its own state, no need to lock here.
            long fftStart = System.currentTimeMillis();
            fourierTransformer.transform();
            long fftEnd = System.currentTimeMillis();
            statsTime(0, (fftEnd - fftStart) * 1000);

            // Lock this while we write to the output buffer.
            synchronized (this) {
                currentPower = sigPower;
                fourierTransformer.getResults(spectrumData);
                drawSpectrum(spectrumCanvas, now, 0, 0);
            }
        }
    }


    /**
     * Draw the current frame of the application.
     * 
     * <p>Applications must override this, and are expected to draw the
     * entire screen into the provided canvas.
     * 
     * <p>This method will always be called after a call to doUpdate(),
     * and also when the screen needs to be re-drawn.
     * 
     * @param   canvas      The Canvas to draw into.
     * @param   now         Current time in ms.  Will be the same as that
     *                      passed to doUpdate(), if there was a preceeding
     *                      call to doUpdate().
     */
    @Override
    protected void doDraw(Canvas canvas, long now) {
        canvas.drawBitmap(waveBitmap, 32, 64, null);
        canvas.drawBitmap(spectrumBitmap, 32, 128, null);
        drawVuMeter(canvas, now, 32, 420);
    }

    
    /**
     * Draw the waveform of the current audio sample into the given canvas.
     * 
     * @param   canvas      The Canvas to draw into.
     * @param   now         Current time in ms.  Will be the same as that
     *                      passed to doUpdate(), if there was a preceeding
     *                      call to doUpdate().
     */
    private void drawWaveform(Canvas canvas, long now, int cx, int cy) {
        canvas.drawColor(0xff000000);
        
        // Calculate a scaling factor.  We want a degree of AGC, but not
        // so much that the waveform is always the same height.
        float max = 0f;
        for (int i = 1; i < 256; ++i)
            if (audioData[i] > max)
                max = audioData[i];
        float scale = (float) Math.pow(1f / (max / 6500f), 0.7) * 4;
        
        float px = 0;
        float py = audioData[0] * scale / 1024f + 32f;
        for (int i = 1; i < 256; ++i) {
            float y = audioData[i] * scale / 1024f + 32f;
            canvas.drawLine(px, py, i, y, screenPaint);
            px = i;
            py = y;
        }
    }

    
    /**
     * Draw the current frame of the application into the given canvas.
     * 
     * @param   canvas      The Canvas to draw into.
     * @param   now         Current time in ms.  Will be the same as that
     *                      passed to doUpdate(), if there was a preceeding
     *                      call to doUpdate().
     */
    private void drawSpectrum(Canvas canvas, long now, int cx, int cy) {
        canvas.drawColor(0xff000000);
        
        // Calculate a scaling factor.  We want a degree of AGC, but not
        // so much that the spectrum is always the same height.
        float max = 0f;
        for (int i = 1; i < FFT_BLOCK / 2; ++i)
            if (spectrumData[i] > max)
                max = spectrumData[i];
        float scale = (float) Math.pow(1f / (max / 0.2f), 0.8) * 5;
       
        float x = cx;
        float y = cy + 256f;
        float w = 256f / (FFT_BLOCK / 2);
        
        screenPaint.setColor(0xffff0000);
        screenPaint.setStyle(Style.STROKE);
        screenPaint.setStyle(Style.FILL);
        canvas.drawLine(x, y - 256f, x + 256f, y - 256f, screenPaint);
       
        paintColor[1] = 1f;
        paintColor[2] = 1f;
        for (int i = 1; i < FFT_BLOCK / 2; ++i) {
            // Cycle the hue angle from 0° to 300°; i.e. red to purple.
            paintColor[0] = (float) i / (float) (FFT_BLOCK / 2) * 300f;
            screenPaint.setColor(Color.HSVToColor(paintColor));
            float bar = y - spectrumData[i] * scale * 256f;
            canvas.drawRect(x, bar, x + w, y, screenPaint);
            x += w;
        }

        x = 32f;
        screenPaint.setColor(0xffffff00);
        screenPaint.setStyle(Style.STROKE);
        canvas.drawRect(x, 420, x + 256f, 440, screenPaint);
        screenPaint.setStyle(Style.FILL);
        canvas.drawRect(x, 424, x + currentPower * 256f, 436, screenPaint);
    }
    

    /**
     * Draw the current frame of the application into the given canvas.
     * 
     * @param   canvas      The Canvas to draw into.
     * @param   now         Current time in ms.  Will be the same as that
     *                      passed to doUpdate(), if there was a preceeding
     *                      call to doUpdate().
     */
    private void drawVuMeter(Canvas canvas, long now, int x, int y) {
//        canvas.drawColor(0xff000000);
        
        screenPaint.setColor(0xffffff00);
        screenPaint.setStyle(Style.STROKE);
        canvas.drawRect(x, y, x + 256f, y + 20f, screenPaint);
        screenPaint.setStyle(Style.FILL);
        screenPaint.setColor(0xff000000);
        canvas.drawRect(x + 1, y + 1, x + 255f, y + 19f, screenPaint);
        screenPaint.setColor(0xffffff00);
        canvas.drawRect(x, y + 4f, x + currentPower * 256f, y + 16f, screenPaint);
    }
    

	// ******************************************************************** //
	// Input Handling.
	// ******************************************************************** //

    /**
	 * Handle key input.
	 * 
     * @param	keyCode		The key code.
     * @param	event		The KeyEvent object that defines the
     * 						button action.
     * @return				True if the event was handled, false otherwise.
	 */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
    	return false;
    }
    
    
    /**
	 * Handle touchscreen input.
	 * 
     * @param	event		The MotionEvent object that defines the action.
     * @return				True if the event was handled, false otherwise.
	 */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
    	int action = event.getAction();
//    	final float x = event.getX();
//        final float y = event.getY();
    	switch (action) {
    	case MotionEvent.ACTION_DOWN:
            break;
        case MotionEvent.ACTION_MOVE:
            break;
    	case MotionEvent.ACTION_UP:
            break;
    	case MotionEvent.ACTION_CANCEL:
            break;
        default:
            break;
    	}

		return true;
    }


    // ******************************************************************** //
    // Save and Restore.
    // ******************************************************************** //

    /**
     * Save the state of the game in the provided Bundle.
     * 
     * @param   icicle      The Bundle in which we should save our state.
     */
    protected void saveState(Bundle icicle) {
//      gameTable.saveState(icicle);
    }


    /**
     * Restore the game state from the given Bundle.
     * 
     * @param   icicle      The Bundle containing the saved state.
     */
    protected void restoreState(Bundle icicle) {
//      gameTable.pause();
//      gameTable.restoreState(icicle);
    }
    

    // ******************************************************************** //
    // Class Data.
    // ******************************************************************** //

    // Debugging tag.
	@SuppressWarnings("unused")
	private static final String TAG = "WindMeter";

    // Audio buffer size, in samples.
    private static final int FFT_BLOCK = 256;

    // Amount by which we decimate the input for each FFT.  We read this
    // many multiples of FFT_BLOCK, but then FFT only the last FFT_BLOCK
    // samples.
    private static final int DECIMATE = 1;

	
	// ******************************************************************** //
	// Private Data.
	// ******************************************************************** //
    
    // Our audio input device.
    private final AudioReader audioReader;
    
    // Buffered audio data, and flag whether it has been processed.
    private short[] audioData;
    private boolean audioProcessed;

    // Fourier Transform calculator we use for calculating the spectrum.
    private final FFTTransformer fourierTransformer;
    
    // Current signal power level.
    private float currentPower = 0;

    // Analysed audio spectrum data.
    private final float[] spectrumData;

    // Bitmap in which we draw the audio waveform display,
    // and the Canvas for drawing into it.
    private Bitmap waveBitmap = null;
    private Canvas waveCanvas = null;

    // Bitmap in which we draw the audio spectrum display,
    // and the Canvas for drawing into it.
    private Bitmap spectrumBitmap = null;
    private Canvas spectrumCanvas = null;

    // Paint used for drawing the display.
    private Paint screenPaint = null;
    float[] paintColor = { 0, 1, 1 };

    // Last touch event position.
    float lastX = 0.0f;
    float lastY = 0.0f;
    
}
