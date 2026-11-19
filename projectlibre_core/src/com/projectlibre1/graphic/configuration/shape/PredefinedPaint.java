/*******************************************************************************
 * The contents of this file are subject to the Common Public Attribution License 
 * Version 1.0 (the "License"); you may not use this file except in compliance with 
 * the License. You may obtain a copy of the License at 
 * http://www.projectlibre.com/license . The License is based on the Mozilla Public 
 * License Version 1.1 but Sections 14 and 15 have been added to cover use of 
 * software over a computer network and provide for limited attribution for the 
 * Original Developer. In addition, Exhibit A has been modified to be consistent 
 * with Exhibit B. 
 *
 * Software distributed under the License is distributed on an "AS IS" basis, 
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for the 
 * specific language governing rights and limitations under the License. The 
 * Original Code is ProjectLibre. The Original Developer is the Initial Developer 
 * and is ProjectLibre Inc. All portions of the code written by ProjectLibre are 
 * Copyright (c) 2012-2019. All Rights Reserved. All portions of the code written by 
 * ProjectLibre are Copyright (c) 2012-2019. All Rights Reserved. Contributor 
 * ProjectLibre, Inc.
 *
 * Alternatively, the contents of this file may be used under the terms of the 
 * ProjectLibre End-User License Agreement (the ProjectLibre License) in which case 
 * the provisions of the ProjectLibre License are applicable instead of those above. 
 * If you wish to allow use of your version of this file only under the terms of the 
 * ProjectLibre License and not to allow others to use your version of this file 
 * under the CPAL, indicate your decision by deleting the provisions above and 
 * replace them with the notice and other provisions required by the ProjectLibre 
 * License. If you do not delete the provisions above, a recipient may use your 
 * version of this file under either the CPAL or the ProjectLibre Licenses. 
 *
 *
 * [NOTE: The text of this Exhibit A may differ slightly from the text of the notices 
 * in the Source Code files of the Original Code. You should use the text of this 
 * Exhibit A rather than the text found in the Original Code Source Code for Your 
 * Modifications.] 
 *
 * EXHIBIT B. Attribution Information for ProjectLibre required
 *
 * Attribution Copyright Notice: Copyright (c) 2012-2019, ProjectLibre, Inc.
 * Attribution Phrase (not exceeding 10 words): 
 * ProjectLibre, open source project management software.
 * Attribution URL: http://www.projectlibre.com
 * Graphic Image as provided in the Covered Code as file: projectlibre-logo.png with 
 * alternatives listed on http://www.projectlibre.com/logo 
 *
 * Display of Attribution Information is required in Larger Works which are defined 
 * in the CPAL as a work which combines Covered Code or portions thereof with code 
 * not governed by the terms of the CPAL. However, in addition to the other notice 
 * obligations, all copies of the Covered Code in Executable and Source Code form 
 * distributed must, as a form of attribution of the original author, include on 
 * each user interface screen the "ProjectLibre" logo visible to all users. 
 * The ProjectLibre logo should be located horizontally aligned with the menu bar 
 * and left justified on the top left of the screen adjacent to the File menu. The 
 * logo must be at least 144 x 31 pixels. When users click on the "ProjectLibre" 
 * logo it must direct them back to http://www.projectlibre.com. 
 *******************************************************************************/
package com.projectlibre1.graphic.configuration.shape;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Paint;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.TexturePaint;
import java.awt.image.BufferedImage;
import java.util.HashMap;

/**
 *
 */
public class PredefinedPaint extends TexturePaint {
	private Color foreground;
	private Color background;
	private int width;
	private int height;
	private int[] points;
	private float alphaValue=1.0f;
	private boolean solid=false;


	public PredefinedPaint(int width, int height, int[] array) {
		this(width, height, array, Color.black, Color.white);
	}
	public PredefinedPaint(PredefinedPaint pattern, Color foreground, Color background) {
		this(pattern.width, pattern.height, pattern.points, foreground,
				background);
	}

	private PredefinedPaint(int width, int height, int[] points, Color foreground,
			Color background) {
		super(createTexture(width, height, points, foreground, background),
				new Rectangle(0, 0, width*tileFactor(width), height*tileFactor(height)));
		this.width = width;
		this.height = height;
		this.foreground = foreground;
		this.background = background;
		this.points = points;
		float sum=0;
		boolean allOn=true;
		for (int i=0; i<points.length;i++){
			sum+=points[i];
			if (points[i]<=0) allOn=false;
		}
		solid=allOn;
		alphaValue = (float) Math.pow(sum/points.length,1.5); //modify brightness
	}

	//tiling tiny (e.g. 4x4) textures over large areas is very slow; repeat the
	//pattern up to >=64px per side so fills need far fewer tile iterations
	private static int tileFactor(int size) {
		return Math.max(1, 64/size);
	}

	private static BufferedImage createTexture(int width, int height,
			int[] array, Color foreground, Color background) {
		int tiledWidth=width*tileFactor(width);
		int tiledHeight=height*tileFactor(height);
		BufferedImage bufferedImage = new BufferedImage(tiledWidth, tiledHeight,BufferedImage.TYPE_INT_ARGB);
		for (int w = 0; w < tiledWidth; w++) {
			for (int h = 0; h < tiledHeight; h++) {
				bufferedImage.setRGB(w, h, array[(w%width) + (h%height) * width] > 0 ? foreground.getRGB() : background.getRGB());
			}
		}
		return bufferedImage;
	}

	public static final PredefinedPaint TRANSPARENT =  new PredefinedPaint(4, 4,
			new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
	public static final PredefinedPaint SOLID = new PredefinedPaint(4, 4,
			new int[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1});
	public static final PredefinedPaint DEFAULT = new PredefinedPaint(4, 4,
			new int[]{1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 1, 0, 1});
	public static final PredefinedPaint SPACED_DOTS = new PredefinedPaint(4, 4,
			new int[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
	public static final PredefinedPaint DIAGONAL = new PredefinedPaint(4, 4,
			new int[]{0, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 0});
	public static final PredefinedPaint DOT_LINE = new PredefinedPaint(4, 4,
			new int[]{1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 1, 0});
	public static final PredefinedPaint DASH_LINE = new PredefinedPaint(1, 4,
			new int[]{1, 1, 0, 0});
	public static final PredefinedPaint DOT_LINE2 = new PredefinedPaint(1, 2,
			new int[]{1, 0});
	public static final PredefinedPaint VERY_SPACED_DOTS = new PredefinedPaint(8, 8,
			new int[]{
			1, 0, 0, 0, 0, 0, 0, 0, 
			0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0
			});
	
	private static Object[][] data = {
	  {"TRANSPARENT",TRANSPARENT},
	  {"SOLID",	SOLID},
	  {"DEFAULT",DEFAULT},
	  {"SPACED_DOTS",SPACED_DOTS},
	  {"VERY_SPACED_DOTS",VERY_SPACED_DOTS},
	  {"DASH_LINE",DASH_LINE},
	  {"DIAGONAL",DIAGONAL},
	  {"DOT_LINE2",DOT_LINE2},
	  {"DOT_LINE",DOT_LINE}
	};
	private static HashMap shapePaintMap = null;
	
	public static HashMap getShapePaints() {
		if (shapePaintMap == null) {
			shapePaintMap = new HashMap();
			for (int i = 0; i < data.length; i++) {
				Object row[] = data[i];
				shapePaintMap.put(row[0], row[1]);
			}
		}
		return shapePaintMap;
	}

	public static PredefinedPaint find(String key) {
		return(PredefinedPaint) getShapePaints().get(key);
	}


	public void applyPaint(Graphics2D g2,boolean texture){
		//if ("SVGGraphics2D".equals(g2.getClass().getSimpleName()))
		if (solid) {
			g2.setColor(foreground); // a solid pattern renders as the plain color; skip the much slower texture fill
		} else if (texture) {
			g2.setPaint(this); // the paint already has the color set
		} else {
			g2.setColor(new Color(bar(foreground.getRed(),background.getRed(),alphaValue),bar(foreground.getGreen(),background.getGreen(),alphaValue),bar(foreground.getBlue(),background.getBlue(),alphaValue)));
		}

	}
	/**
	 * Fill a rectangle with a texture paint by drawing the paint's tile image across it. Drawing an
	 * image is accelerated on screen, while filling with a TexturePaint is rendered in software
	 * there, which made shading large areas (weekends and holidays in the Gantt) slow to repaint.
	 * The result is the same pattern, aligned to the paint's anchor.
	 */
	public static void fillTiled(Graphics2D g2, TexturePaint paint, int x, int y, int w, int h) {
		fillTiled(g2, paint, new Rectangle(x, y, w, h));
	}

	/** Same as {@link #fillTiled(Graphics2D, TexturePaint, int, int, int, int)}, covering the same pixels as filling the rectangle would. */
	public static void fillTiled(Graphics2D g2, TexturePaint paint, Rectangle2D r) {
		if (r.getWidth() <= 0 || r.getHeight() <= 0)
			return;
		BufferedImage tile = paint.getImage();
		Rectangle2D anchor = paint.getAnchorRect();
		int tw = tile.getWidth(), th = tile.getHeight();
		if (!"sun.java2d.SunGraphics2D".equals(g2.getClass().getName()) // printing and PDF export: keep the real pattern fill
				|| anchor.getWidth() != tw || anchor.getHeight() != th
				|| anchor.getX() != Math.rint(anchor.getX()) || anchor.getY() != Math.rint(anchor.getY())) { // stretched or offset tile: leave it to the paint
			Paint old = g2.getPaint();
			g2.setPaint(paint);
			g2.fill(r);
			g2.setPaint(old);
			return;
		}
		int ax = (int) anchor.getX(), ay = (int) anchor.getY();
		Shape oldClip = g2.getClip();
		g2.clip(r); // a rectangle clip covers the pixels the fill would, and keeps the fast path
		int x0 = (int) Math.floor(r.getX()), y0 = (int) Math.floor(r.getY());
		double x1 = r.getMaxX(), y1 = r.getMaxY();
		int startX = ax + Math.floorDiv(x0 - ax, tw) * tw;
		int startY = ay + Math.floorDiv(y0 - ay, th) * th;
		for (int ty = startY; ty < y1; ty += th)
			for (int tx = startX; tx < x1; tx += tw)
				g2.drawImage(tile, tx, ty, null);
		g2.setClip(oldClip);
	}

	/** True if the shape is an axis-aligned rectangle, which {@link #fillTiled} can fill. */
	public static boolean isRectangle(Shape shape) {
		if (shape instanceof Rectangle2D)
			return true;
		Rectangle2D b = shape.getBounds2D();
		double[] c = new double[6];
		int corners = 0;
		for (java.awt.geom.PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
			int type = it.currentSegment(c);
			if (type == java.awt.geom.PathIterator.SEG_CLOSE)
				continue;
			if (type != java.awt.geom.PathIterator.SEG_MOVETO && type != java.awt.geom.PathIterator.SEG_LINETO)
				return false;
			boolean onX = c[0] == b.getMinX() || c[0] == b.getMaxX();
			boolean onY = c[1] == b.getMinY() || c[1] == b.getMaxY();
			if (!onX || !onY || ++corners > 5)
				return false;
		}
		return corners >= 4;
	}

	private int bar(float a,float b,float w){
		return Math.round(a*w+(1.0f-w)*b);
	}

    
}
