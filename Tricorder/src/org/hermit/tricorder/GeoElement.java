
/**
 * Tricorder: turn your phone into a tricorder.
 * 
 * This is an Android implementation of a Star Trek tricorder, based on
 * the phone's own sensors.  It's also a demo project for sensor access.
 *
 *   This program is free software; you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License version 2
 *   as published by the Free Software Foundation (see COPYING).
 * 
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 */


package org.hermit.tricorder;

import org.hermit.android.instruments.Element;
import org.hermit.android.instruments.TextAtom;
import org.hermit.utils.CharFormatter;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.location.Location;
import android.util.Log;


/**
 * A view which displays geographical data.
 */
class GeoElement
	extends Element
{

	// ******************************************************************** //
	// Constructor.
	// ******************************************************************** //

	/**
	 * Set up this view.
	 * 
	 * @param	context			Parent application context.
	 * @param	gridCol			Colour for the framing elements.
	 * @param	plotCol			Colour for the data display.
	 * @param	course			If true, show course info.
	 */
	public GeoElement(Tricorder context,
					  int gridCol, int plotCol, boolean course)
	{
		super(context, gridCol, plotCol);
		
		final String[] hFields = {
			getRes(R.string.title_network), "xx", "999 days 23h",
		};
		final String[] bFields = {
			"W122° 59.999'", getRes(R.string.lab_alt), "-9999.9m",
		};
		final String[] cFields = {
			getRes(R.string.lab_head), "999°", "xx",
			getRes(R.string.lab_speed), "999.9m/s",
		};
		
		headerBar = new HeaderBarElement(context, hFields);
		headerBar.setBarColor(gridCol);
 
		posFields = new TextAtom(context, bFields, 2);
        posBuffers = posFields.getBuffer();
		if (course) {
			courseFields = new TextAtom(context, cFields, 1);
	        courseBuffers = courseFields.getBuffer();
		} else {
			courseFields = null;
            courseBuffers = null;
		}

    	// Create the left-side bar.
    	rightBar = new Element(context);
    	rightBar.setBackgroundColor(gridCol);
	}

	   
    // ******************************************************************** //
	// Geometry Management.
	// ******************************************************************** //

    /**
     * This is called during layout when the size of this element has
     * changed.  This is where we first discover our size, so set
     * our geometry to match.
     * 
	 * @param	bounds		The bounding rect of this element within
	 * 						its parent View.
     */
	@Override
	public void setGeometry(Rect bounds) {
		super.setGeometry(bounds);

		int y = bounds.top;
		
		// First position the header bar.
		int headHeight = headerBar.getPreferredHeight();
		headerBar.setGeometry(new Rect(bounds.left, y,
									   bounds.right, y + headHeight));
		y += headHeight + getInnerGap();

        int bar = getSidebarWidth();
        int pad = getInterPadding();
		int ex = bounds.right - bar - pad;

		// Position the right bar, except we don't know the height yet.
		// So just make most of a Rect.
		Rect lbrect = new Rect(bounds.right - bar, y, bounds.right, -1);

		// Set up the position fields.
		int posHeight = posFields.getPreferredHeight();
		posFields.setGeometry(new Rect(bounds.left, y, ex, y + posHeight));
		y += posHeight;
		
		// Set up the course fields, if present.
		if (courseFields != null) {
			int courseHeight = courseFields.getPreferredHeight();
			courseFields.setGeometry(new Rect(bounds.left, y, ex, y + courseHeight));
			y += courseHeight;
		}
		
		// Now finalize the right bar.
		lbrect.bottom = y;
		rightBar.setGeometry(lbrect);
	}


    /**
     * Get the minimum height needed to fit all the text.
     * 
     * @return          The minimum height needed to fit all the text.
     *                  Returns zero if setTextFields() hasn't been called.
     */
    @Override
    public int getPreferredWidth() {
        int bar = getSidebarWidth();
        int pad = getInterPadding();
        int w = headerBar.getPreferredWidth();
        int p = posFields.getPreferredWidth() + bar + pad;
        if (p > w)
            w = p;
        if (courseFields != null) {
            int c = courseFields.getPreferredWidth() + bar + pad;
            if (c > w)
                w = c;
        }
        return w;
    }
    

	/**
	 * Get the minimum height needed to fit all the text.
	 * 
	 * @return			The minimum height needed to fit all the text.
	 * 					Returns zero if setTextFields() hasn't been called.
	 */
	@Override
	public int getPreferredHeight() {
		return headerBar.getPreferredHeight() +
						posFields.getPreferredHeight() +
						(courseFields == null ? 0 :
								courseFields.getPreferredHeight());
	}
	

    // ******************************************************************** //
	// Appearance.
	// ******************************************************************** //

	/**
	 * Set the background colour of this element.
	 * 
     * @param	grid			Colour for drawing a data scale / grid.
     * @param	plot			Colour for drawing data plots.
	 */
	@Override
	public void setDataColors(int grid, int plot) {
		headerBar.setDataColors(grid, plot);
		posFields.setDataColors(grid, plot);
		if (courseFields != null)
			courseFields.setDataColors(grid, plot);
		rightBar.setDataColors(grid, plot);
	}
	

	// ******************************************************************** //
	// Data Management.
	// ******************************************************************** //

    /**
     * Set a specific text value displayed in this view's header bar.
     * 
     * @param   row             Row of the field to change.
     * @param   col             Column of the field to change.
     * @param   text            The new text field value.
     */
    protected void setText(int row, int col, String text) {
        headerBar.setText(row, col, text);
    }


	/**
	 * Set the status part of the header text.
	 * 
	 * @param	stat			The new status.  If null, there's no
	 * 							status except "OK"; we'll display the data
	 * 							age instead.
	 */
	protected void setStatus(String stat) {
		Log.i(TAG, "GeoElement set status: " + stat);
		currentStatus = stat;
		final long now = System.currentTimeMillis();
		displayLocation(currentLocation, now);
	}

	
	/**
	 * Update the display with the given location.
	 * 
	 * @param	loc			The new location.
	 */
	public void setValue(Location loc) {
		currentLocation = loc;
		final long now = System.currentTimeMillis();
		displayLocation(currentLocation, now);
	}


	/**
	 * A 1-second tick event.  Can be used for housekeeping and
	 * async updates.  We use it to update the age of our data.
	 * 
	 * @param	time				The current time in millis.
	 */
	public void tick(long time) {
		displayLocation(currentLocation, time);
	}


	/**
	 * Display the given location.
	 * 
	 * @param	loc			The location to display.
	 * @param	now			Current time in ms.
	 */
	private void displayLocation(Location loc, long now) {
		// Draw the network provider fields.
		long age = loc == null ? 0 : (now - loc.getTime()) / 1000;
		int dcol = dataColour(age);
		
		if (currentStatus != null)
			headerBar.setText(0, 2, currentStatus);
		else if (loc == null)
			headerBar.setText(0, 2, getRes(R.string.msgNoData));
		else
			headerBar.setText(0, 2, elapsed(age));

		formatLocation(loc, posBuffers, courseBuffers);
		posFields.setTextColor(dcol);
		if (courseFields != null)
			courseFields.setTextColor(dcol);
	}
	
	
	/**
	 * Return the colour which should be used to display data of a given age.
	 * 
	 * @param	age			Data age in seconds.
	 * @return				A colour which indicates its age.
	 */
	private int dataColour(long age) {
		int dataColor = COL_OLD_DATA;
		for (int i = 0; i < COL_DATA.length; ++i) {
			if (age <= COL_DATA[i][0] || COL_DATA[i][0] < 0) {
				dataColor = COL_DATA[i][1];
				break;
			}
		}

		return dataColor;
	}

	
	/**
	 * Format the given location into a string array.
	 * 
	 * @param l				Location to format.
	 * @param pos			Array to format the position into.
	 * @param course		Array to format the course data into; null
	 * 						if not needed.
	 */
	private void formatLocation(Location l, char[][][] pos, char[][][] course)
	{
		if (l == null)
			return;

		CharFormatter.formatDegMin(pos[0][0], 0, l.getLatitude(), 'N', 'S', false);
		CharFormatter.formatDegMin(pos[1][0], 0, l.getLongitude(), 'E', 'W', false);

		if (l.hasAltitude()) {
		    CharFormatter.formatString(pos[0][1], 0, getRes(R.string.lab_alt), -1);
		    CharFormatter.formatFloat(pos[0][2], 0, l.getAltitude(), 7, 1, true);
		    pos[0][2][7] = 'm';
		} else {
		    CharFormatter.blank(pos[0][1], 0, -1);
		    CharFormatter.blank(pos[0][2], 0, -1);
		}
		CharFormatter.formatString(pos[1][1], 0, getRes(R.string.lab_acc), -1);
		CharFormatter.formatFloat(pos[1][2], 0, l.getAccuracy(), 7, 1, false);
		pos[1][2][7] = 'm';

		if (course != null) {
		    CharFormatter.formatString(course[0][0], 0, getRes(R.string.lab_head), -1);
		    if (l.hasBearing()) {
		        CharFormatter.formatInt(course[0][1], 0, (int) l.getBearing(), 3, true);
		        course[0][1][3] = '°';
		    } else
		        CharFormatter.blank(course[0][1], 0, -1);
		    CharFormatter.blank(course[0][2], 0, -1);
		    CharFormatter.formatString(course[0][3], 0, getRes(R.string.lab_speed), -1);
		    if (l.hasSpeed()) {
		        CharFormatter.formatFloat(course[0][4], 0, l.getSpeed(), 5, 1, false);
		        CharFormatter.formatString(course[0][4], 5, "m/s", -1);
		    } else
		        CharFormatter.blank(course[0][4], 0, -1);
		}
	}


	/**
	 * Convert an elapsed time into a user-friendly textual description.
	 * 
	 * @param millis			Elapsed time in seconds.
	 * @return					A human-friendly description of that time.
	 */
	private static final String elapsed(long secs) {
		long mins = secs / 60;
		long hours = mins / 60;
		long days = hours / 24;
		
		if (mins < 1)
			return "" + secs + "s";
		
		if (mins < 5)
			return "" + mins + "m " + secs % 60 + "s";
		if (hours < 1)
			return "" + mins + "m";
		
		if (hours < 5)
			return "" + hours + "h " + mins % 60 + "m";
		if (days < 1)
			return "" + hours + "h";
		
		if (days < 5)
			return "" + days + " days " + hours % 24 + "h";
		return "" + days + " days";
	}
	

	// ******************************************************************** //
	// View Drawing.
	// ******************************************************************** //
	
	/**
	 * This method is called to ask the element to draw itself.
	 * 
	 * @param	canvas		Canvas to draw into.
	 * @param	now				Current system time in ms.
	 */
	@Override
	public void draw(Canvas canvas, long now) {
		super.draw(canvas, now);
		
		headerBar.draw(canvas, now);
		posFields.draw(canvas, now);
		if (courseFields != null)
			courseFields.draw(canvas, now);
		rightBar.draw(canvas, now);
	}


    // ******************************************************************** //
    // Class Data.
    // ******************************************************************** //

    // Debugging tag.
	private static final String TAG = "tricorder";
	
	// Colour for data which is officially stale; i.e. cached from a
	// previous run.
	private static final int COL_OLD_DATA = 0xffff0000;

	// Data display colours.  We use different colours to indicate the age
	// of data.  This array contains maximum age in seconds and the 
	// corresponding colour; -1 means infinitely old.
	private static final int[][] COL_DATA = {
		{  10, 0xff00ffff },
		{  30, 0xff00ff00 },
		{ 120, 0xffffff00 },
		{ 600, 0xffff9000 },
		{  -1, COL_OLD_DATA },
	};

	
	// ******************************************************************** //
	// Private Data.
	// ******************************************************************** //

	// Display panes for the network and GPS locations, with heading,
	// position, and course data for each.
	private HeaderBarElement headerBar;
	private TextAtom posFields;
	private TextAtom courseFields;

	// The left-side bar (just a solid colour bar).
	private Element rightBar;

    // Text field buffers for the data display.
    private char[][][] posBuffers;
    private char[][][] courseBuffers;
   
	// The most recent location we got.  null if none yet.
	private Location currentLocation = null;

	// The current availability status.  If null, there's no
	// status except "OK"; we'll display the data age instead.
	private String currentStatus = null;

}

