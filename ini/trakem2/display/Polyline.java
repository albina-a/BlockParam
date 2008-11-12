/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2008 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/

package ini.trakem2.display;

import ij.gui.Toolbar;
import ij.measure.Calibration;

import ini.trakem2.Project;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.vector.VectorString3D;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** A sequence of points that make multiple chained line segments. */
public class Polyline extends ZDisplayable implements Line3D {

	/**The number of points.*/
	protected int n_points;
	/**The array of clicked x,y points as [2][n].*/
	protected double[][] p = new double[2][0];
	/**The array of Layers over which the points of this pipe live */
	protected long[] p_layer = new long[0];

	/** New empty Polyline. */
	public Polyline(Project project, String title) {
		super(project, title, 0, 0);
		addToDatabase();
		n_points = 0;
	}

	public Polyline(Project project, long id, String title, double width, double height, float alpha, boolean visible, Color color, boolean locked, AffineTransform at) {
		super(project, id, title, locked, at, width, height);
		this.visible = visible;
		this.alpha = alpha;
		this.visible = visible;
		this.color = color;
		this.n_points = -1; //used as a flag to signal "I have points, but unloaded"
	}

	/** Reconstruct from XML. */
	public Polyline(final Project project, final long id, final HashMap ht, final HashMap ht_links) {
		super(project, id, ht, ht_links);
		// parse specific data
		for (Iterator it = ht.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			String key = (String)entry.getKey();
			String data = (String)entry.getValue();
			if (key.equals("d")) {
				// parse the points
				// parse the SVG points data
				ArrayList al_p = new ArrayList();
				// sequence is: M p[0][0],p[1][0] L p[0][1],p[1][1] L p[0][2],p[1][2] ...
				// first point:
				int i_start = data.indexOf('M');
				int i_L = data.indexOf('L', i_start+1);
				int next = 0;
				while (-1 != i_L) {
					if (p[0].length == next) enlargeArrays();
					// parse the point
					// 'X'
					int i_comma = data.indexOf(',', i_start+1);
					p[0][next] = Double.parseDouble(data.substring(i_start+1, i_comma));
					// 'Y'
					i_L = data.indexOf('L', i_comma);
					int i_end = i_L;
					if (-1 == i_L) i_end = data.length();
					p[1][next] = Double.parseDouble(data.substring(i_comma+1, i_end));
					// prepare next point
					i_start = i_L;
					next++;
				}
			} else if (key.equals("layer_ids")) {
				// parse comma-separated list of layer ids. Creates empty Layer instances with the proper id, that will be replaced later.
				final String[] layer_ids = data.replaceAll(" ", "").trim().split(",");
				this.p_layer = new long[layer_ids.length];
				for (int i=0; i<layer_ids.length; i++) {
					if (i == p_layer.length) enlargeArrays();
					this.p_layer[i] = Long.parseLong(layer_ids[i]);
				}
			}
		}
		// finish up
		this.n_points = p[0].length;
	}

	/**Increase the size of the arrays by 5.*/
	synchronized protected void enlargeArrays() {
		//catch length
		int length = p[0].length;
		//make copies
		double[][] p_copy = new double[2][length + 5];
		long[] p_layer_copy = new long[length + 5];
		//copy values
		System.arraycopy(p[0], 0, p_copy[0], 0, length);
		System.arraycopy(p[1], 0, p_copy[1], 0, length);
		System.arraycopy(p_layer, 0, p_layer_copy, 0, length);
		//assign them
		this.p = p_copy;
		this.p_layer = p_layer_copy;
	}

	/**Find a point in an array, with a precision dependent on the magnification. Only points in the given  layer are considered, the rest are ignored. Returns -1 if none found. */
	synchronized protected int findPoint(final int x_p, final int y_p, final long layer_id, final double mag) {
		int index = -1;
		double d = (10.0D / mag);
		if (d < 2) d = 2;
		double min_dist = Double.MAX_VALUE;
		for (int i=0; i<n_points; i++) {
			double dist = Math.abs(x_p - p[0][i]) + Math.abs(y_p - p[1][i]);
			if (layer_id == p_layer[i] && dist <= d && dist <= min_dist) {
				min_dist = dist;
				index = i;
			}
		}
		return index;
	}

	/**Remove a point from the bezier backbone and its two associated control points.*/
	synchronized protected void removePoint(final int index) {
		// check preconditions:
		if (index < 0) {
			return;
		} else if (n_points - 1 == index) {
			//last point out
			n_points--;
		} else {
			//one point out (but not the last)
			--n_points;

			// shift all points after 'index' one position to the left:
			for (int i=index; i<n_points; i++) {
				p[0][i] = p[0][i+1];		//the +1 doesn't fail ever because the n_points has been adjusted above, but the arrays are still the same size. The case of deleting the last point is taken care above.
				p[1][i] = p[1][i+1];
				p_layer[i] = p_layer[i+1];
			}
		}

		//update in database
		updateInDatabase("points");
	}

	/**Move backbone point by the given deltas.*/
	public void dragPoint(final int index, final int dx, final int dy) {
		if (index < 0 || index >= n_points) return;
		p[0][index] += dx;
		p[1][index] += dy;
	}

	/**Add a point either at the end or between two existing points, with accuracy depending on magnification. The width of the new point is that of the closest point after which it is inserted.*/
	synchronized protected int addPoint(int x_p, int y_p, long layer_id, double magnification) {
		if (-1 == n_points) setupForDisplay(); //reload
		//lookup closest point and then get the closest clicked point to it
		int index = findPoint(x_p, y_p, layer_id, magnification);
		//check array size
		if (p[0].length == n_points) {
			enlargeArrays();
		}
		//decide:
		if (0 == n_points || 1 == n_points || index + 1 == n_points) {
			//append at the end
			p[0][n_points] = x_p;
			p[1][n_points] = y_p;
			p_layer[n_points] = layer_id;
			index = n_points;
		} else if (-1 == index) {
			// decide whether to append at the end or prepend at the beginning
			// compute distance in the 3D space to the first and last points
			final double lz = layer_set.getLayer(layer_id).getZ();
			final double p0z =layer_set.getLayer(p_layer[0]).getZ();
			final double pNz =layer_set.getLayer(p_layer[n_points -1]).getZ();
			double sqdist0 =   (p[0][0] - x_p) * (p[0][0] - x_p)
				         + (p[1][0] - y_p) * (p[1][0] - y_p)
					 + (lz - p0z) * (lz - p0z);
			double sqdistN =   (p[0][n_points-1] - x_p) * (p[0][n_points-1] - x_p)
				         + (p[1][n_points-1] - y_p) * (p[1][n_points-1] - y_p)
					 + (lz - pNz) * (lz - pNz);
			if (sqdistN < sqdist0) {
				//append at the end
				p[0][n_points] = x_p;
				p[1][n_points] = y_p;
				p_layer[n_points] = layer_id;
				index = n_points;
			} else {
				// prepend at the beginning
				for (int i=n_points-1; i>-1; i--) {
					p[0][i+1] = p[0][i];
					p[1][i+1] = p[1][i];
					p_layer[i+1] = p_layer[i];
				}
				p[0][0] = x_p;
				p[1][0] = y_p;
				p_layer[0] = layer_id;
				index = 0;
			}
		} else {
			//insert at index:
			index++; //so it is added after the closest point;
			// 1 - copy second half of array
			int sh_length = n_points -index;
			double[][] p_copy = new double[2][sh_length];
			long[] p_layer_copy = new long[sh_length];
			System.arraycopy(p[0], index, p_copy[0], 0, sh_length);
			System.arraycopy(p[1], index, p_copy[1], 0, sh_length);
			System.arraycopy(p_layer, index, p_layer_copy, 0, sh_length);
			// 2 - insert value into 'p' (the two control arrays get the same value)
			p[0][index] = x_p;
			p[1][index] = y_p;
			p_layer[index] = layer_id;
			// 3 - copy second half into the array
			System.arraycopy(p_copy[0], 0, p[0], index+1, sh_length);
			System.arraycopy(p_copy[1], 0, p[1], index+1, sh_length);
			System.arraycopy(p_layer_copy, 0, p_layer, index+1, sh_length);
		}
		//add one up
		this.n_points++;

		return index;
	}

	public void paint(final Graphics2D g, final double magnification, final boolean active, final int channels, final Layer active_layer) {
		if (0 == n_points) return;
		if (-1 == n_points) {
			// load points from the database
			setupForDisplay();
		}
		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}

		// local pointers, since they may be transformed
		int n_points = this.n_points;
		double[][] p = this.p;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			n_points = p[0].length;
		}

		final boolean no_color_cues = "true".equals(project.getProperty("no_color_cues"));

		final long layer_id = active_layer.getId();

		final double z_current = active_layer.getZ();

		// draw/fill points
		for (int j=0; j<n_points; j++) {
			if (no_color_cues) {
				// paint a tiny bit where it should!
				g.setColor(this.color);
			}

			final boolean active_point = layer_id == p_layer[j];

			if (no_color_cues) {
				if (active_point) {
					// paint normally
				} else {
					// else if crossed the current layer, paint segment as well
					if (0 == j) continue;
					double z1 = layer_set.getLayer(p_layer[j-1]).getZ();
					double z2 = layer_set.getLayer(p_layer[j]).getZ();
					if ( (z1 < z_current && z_current < z2)
					  || (z2 < z_current && z_current < z1) ) {
						// paint normally, in this pipe's color
					} else {
						continue;
					}
				}
			} else {
				double z = layer_set.getLayer(p_layer[j]).getZ();
				if (z < z_current) g.setColor(Color.red);
				else if (z == z_current) g.setColor(this.color);
				else g.setColor(Color.blue);
			}

			if (j < n_points -1) {
				g.drawLine((int)p[j][0], (int)p[j][1], (int)p[j+1][0], (int)p[j+1][1]);
			}

			if (active_point) {
				g.setColor(this.color);
				//draw big ovals
				DisplayCanvas.drawHandle(g, (int)p[0][j], (int)p[1][j], magnification);
			}
		}

		//Transparency: fix alpha composite back to original.
		if (null != original_composite) {
			g.setComposite(original_composite);
		}
	}

	public void keyPressed(KeyEvent ke) {
		//Utils.log2("Pipe.keyPressed not implemented.");
	}

	/**Helper vars for mouse events. It's safe to have them static since only one Pipe will be edited at a time.*/
	static private int index;
	static private boolean is_new_point = false;

	public void mousePressed(MouseEvent me, int x_p, int y_p, double mag) {
		// transform the x_p, y_p to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x_p, y_p);
			x_p = (int)po.x;
			y_p = (int)po.y;
		}

		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool) {

			final long layer_id = Display.getFrontLayer(this.project).getId();

			if (me.isControlDown() && me.isShiftDown()) {
				index = Displayable.findNearestPoint(p, n_points, x_p, y_p);
			} else {
				index = findPoint(x_p, y_p, layer_id, mag);
			}

			if (-1 != index) {
				if (me.isControlDown() && me.isShiftDown() && p_layer[index] == Display.getFrontLayer(this.project).getId()) {
					//delete point
					removePoint(index);
					index = -1;
					repaint(false);
					return;
				}
			}


			if (-1 != index && layer_id != p_layer[index]) index = -1; // disable!
			//if no conditions are met, attempt to add point
			else if (-1 == index && !me.isShiftDown() && !me.isAltDown()) {
				//add a new point
				index = addPoint(x_p, y_p, layer_id, mag);
				is_new_point = true;
				repaint(false);
				return;
			}
		}
	}

	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		// transform to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double p = inverseTransformPoint(x_p, y_p);
			x_p = (int)p.x;
			y_p = (int)p.y;
			final Point2D.Double pd = inverseTransformPoint(x_d, y_d);
			x_d = (int)pd.x;
			y_d = (int)pd.y;
			final Point2D.Double pdo = inverseTransformPoint(x_d_old, y_d_old);
			x_d_old = (int)pdo.x;
			y_d_old = (int)pdo.y;
		}

		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool) {
			//if a point in the backbone is found, then:
			if (-1 != index && !me.isAltDown() && !me.isShiftDown()) {
				dragPoint(index, x_d - x_d_old, y_d - y_d_old);
				repaint(false);
				return;
			}
		}
	}

	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {

		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool) {
			repaint(); //needed at least for the removePoint
		}

		//update points in database if there was any change
		if (-1 != index) {
			if (is_new_point) {
				// update all points, since the index may have changed
				updateInDatabase("points");
			} else if (-1 != index && index != n_points) { //second condition happens when the last point has been removed
				// not implemented // updateInDatabase(getUpdatePointForSQL(index));
				// Instead:
				updateInDatabase("points");
			} else if (index != n_points) { // don't do it when the last point is removed
				// update all
				updateInDatabase("points");
			}
			updateInDatabase("dimensions");
		} else if (x_r != x_p || y_r != y_p) {
			updateInDatabase("dimensions");
		}

		repaint(true);

		// reset
		is_new_point = false;
		index = -1;
	}

	synchronized protected void calculateBoundingBox(final boolean adjust_position) {
		double min_x = Double.MAX_VALUE;
		double min_y = Double.MAX_VALUE;
		double max_x = 0.0D;
		double max_y = 0.0D;
		if (0 == n_points) {
			this.width = this.height = 0;
			layer_set.updateBucket(this);
			return;
		}
		// check the points
		for (int i=0; i<n_points; i++) {
			if (p[0][i] < min_x) min_x = p[0][i];
			if (p[1][i] < min_y) min_y = p[1][i];
			if (p[0][i] > max_x) max_x = p[0][i];
			if (p[1][i] > max_y) max_y = p[1][i];
		}

		this.width = max_x - min_x;
		this.height = max_y - min_y;

		if (adjust_position) {
			// now readjust points to make min_x,min_y be the x,y
			for (int i=0; i<n_points; i++) {
				p[0][i] -= min_x;	p[1][i] -= min_y;
			}
			this.at.translate(min_x, min_y); // not using super.translate(...) because a preConcatenation is not needed; here we deal with the data.
			updateInDatabase("transform");
		}
		updateInDatabase("dimensions");

		layer_set.updateBucket(this);
	}

	/**Release all memory resources taken by this object.*/
	synchronized public void destroy() {
		super.destroy();
		p = null;
		p_layer = null;
	}

	/**Release memory resources used by this object: namely the arrays of points, which can be reloaded with a call to setupForDisplay()*/
	synchronized public void flush() {
		p = null;
		p_layer = null;
		n_points = -1; // flag that points exist but are not loaded
	}

	public void repaint() {
		repaint(true);
	}

	/**Repaints in the given ImageCanvas only the area corresponding to the bounding box of this Pipe. */
	public void repaint(boolean repaint_navigator) {
		//TODO: this could be further optimized to repaint the bounding box of the last modified segments, i.e. the previous and next set of interpolated points of any given backbone point. This would be trivial if each segment of the Bezier curve was an object.
		Rectangle box = getBoundingBox(null);
		calculateBoundingBox(true);
		box.add(getBoundingBox(null));
		Display.repaint(layer_set, this, box, 5, repaint_navigator);
	}

	/**Make this object ready to be painted.*/
	synchronized private void setupForDisplay() {
		// load points
		/* Database storage not implemented yet
		if (null == p) {
			ArrayList al = project.getLoader().fetchPolylinePoints(id);
			n_points = al.size();
			p = new double[2][n_points];
			p_layer = new long[n_points];
			Iterator it = al.iterator();
			int i = 0;
			while (it.hasNext()) {
				Object[] ob = (Object[])it.next();
				p[0][i] = ((Double)ob[0]).doubleValue();
				p[1][i] = ((Double)ob[1]).doubleValue();
				p_layer[i] = ((Long)ob[7]).longValue();
				i++;
			}
		}
		*/
	}

	/** The exact perimeter of this pipe, in integer precision. */
	synchronized public Polygon getPerimeter() {
		if (null == p || p[0].length < 2) return new Polygon();

		// local pointers, since they may be transformed
		int n_points = this.n_points;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			n_points = p[0].length;
		}
		int[] x = new int[n_points];
		int[] y = new int[n_points];
		for (int i=0; i<n_points; i++) {
			x[i] = (int)p[0][i];
			y[i] = (int)p[1][i];
		}
		return new Polygon(x, y, n_points);
	}

	public boolean isDeletable() {
		return 0 == n_points;
	}

	/** The number of points in this pipe. */
	public int length() {
		if (-1 == n_points) setupForDisplay();
		return n_points;
	}

	public boolean contains(final Layer layer, int x, int y) {
		Display front = Display.getFront();
		double radius = 10;
		if (null != front) {
			double mag = front.getCanvas().getMagnification();
			radius = (10.0D / mag);
			if (radius < 2) radius = 2;
		}
		// else assume fixed radius of 10 around the line

		final long lid = layer.getId();
		final double z = layer.getZ();

		for (int i=0; i<n_points; i++) {
			if (lid == p_layer[i]) {
				// check both lines:
				if (i > 0 && Displayable.distancePointToLine(x, y, p[0][i-1], p[1][i-1], p[0][i], p[1][i]) < radius) {
					return true;
				}
				if (i < n_points && Displayable.distancePointToLine(x, y, p[0][i], p[1][i], p[0][i+1], p[1][i+1]) < radius) {
					return true;
				}
			} else if (i > 0) {
				double z1 = layer_set.getLayer(p_layer[i-1]).getZ();
				double z2 = layer_set.getLayer(p_layer[i]).getZ();
				if ( (z1 < z && z < z2)
				  || (z2 < z && z < z1) ) {
					// line between j and j-1 crosses the given layer
					if (Displayable.distancePointToLine(x, y, p[0][i-1], p[1][i-1], p[0][i], p[1][i]) < radius) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/* Scan the Display and link Patch objects that lay under this Pipe's bounding box. */
	public void linkPatches() { // TODO needs to check all layers!!
		unlinkAll(Patch.class);
		// sort points by layer id
		final HashMap<Long,ArrayList<Integer>> m = new HashMap<Long,ArrayList<Integer>>();
		for (int i=0; i<n_points; i++) {
			ArrayList<Integer> a = m.get(p_layer[i]);
			if (null == a) {
				a = new ArrayList<Integer>();
				m.put(p_layer[i], a);
			}
			a.add(i);
		}
		// For each layer id, search patches whose perimeter includes
		// one of the backbone points in this path:
		for (Map.Entry<Long,ArrayList<Integer>> e : m.entrySet()) {
			final Layer layer = layer_set.getLayer(e.getKey().longValue());
			for (Displayable patch : layer.getDisplayables(Patch.class)) {
				final Polygon perimeter = patch.getPerimeter();
				for (Integer in : e.getValue()) {
					final int i = in.intValue();
					if (perimeter.contains(p[0][i], p[1][i])) {
						this.link(patch);
						break;
					}
				}
			}
		}
	}

	/** Returns the layer of lowest Z coordinate where this ZDisplayable has a point in, or the creation layer if no points yet. */
	public Layer getFirstLayer() {
		if (0 == n_points) return this.layer;
		if (-1 == n_points) setupForDisplay(); //reload
		Layer la = this.layer;
		double z = Double.MAX_VALUE;
		for (int i=0; i<n_points; i++) {
			Layer layer = layer_set.getLayer(p_layer[i]);
			if (layer.getZ() < z) la = layer;
		}
		return la;
	}

	/** Exports data. */
	synchronized public void exportXML(StringBuffer sb_body, String indent, Object any) {
		sb_body.append(indent).append("<t2_polyline\n");
		String in = indent + "\t";
		super.exportXML(sb_body, in, any);
		if (-1 == n_points) setupForDisplay(); // reload
		//if (0 == n_points) return;
		String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"fill:none;stroke-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";stroke-width:1.0px;stroke-opacity:1.0\"\n");
		if (n_points > 0) {
			sb_body.append(in).append("d=\"M");
			for (int i=0; i<n_points-1; i++) {
				sb_body.append(" ").append(p[0][i]).append(",").append(p[1][i]).append(" L");
			}
			sb_body.append(" ").append(p[0][n_points-1]).append(',').append(p[1][n_points-1]).append("\"\n");
			sb_body.append(in).append("layer_ids=\""); // different from 'layer_id' in superclass
			for (int i=0; i<n_points; i++) {
				sb_body.append(p_layer[i]);
				if (n_points -1 != i) sb_body.append(",");
			}
			sb_body.append("\"\n");
		}
		sb_body.append(indent).append("/>\n");
	}

	/** Exports to type t2_polyline. */
	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		String type = "t2_polyline";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_polyline EMPTY>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
		sb_header.append(indent).append(TAG_ATTR1).append(type).append(" d").append(TAG_ATTR2)
		;
	}

	/** Performs a deep copy of this object, without the links. */
	synchronized public Displayable clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		final Polyline copy = new Polyline(pr, nid, null != title ? title.toString() : null, width, height, alpha, this.visible, new Color(color.getRed(), color.getGreen(), color.getBlue()), this.locked, (AffineTransform)this.at.clone());
		// The data:
		if (-1 == n_points) setupForDisplay(); // load data
		copy.n_points = n_points;
		copy.p = new double[][]{(double[])this.p[0].clone(), (double[])this.p[1].clone()};
		copy.p_layer = (long[])this.p_layer.clone();
		copy.addToDatabase();

		return copy;
	}

	/** Calibrated. */
	synchronized public List generateTriangles(double scale, int parallels, int resample) {
		if (n_points < 2) return null;
		// check minimum requirements.
		if (parallels < 3) parallels = 3;
		//
		final double[][][] all_points = generateJoints(parallels, resample, layer_set.getCalibrationCopy());
		return Pipe.generateTriangles(all_points, scale);
	}

	private double[][][] generateJoints(final int parallels, final int resample, final Calibration cal) {
		if (-1 == n_points) setupForDisplay();
		
		// local pointers, since they may be transformed
		int n_points = this.n_points;
		double[][] p = this.p;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			n_points = p[0].length;
		}
		double[] p_width = new double[n_points];
		double[] z_values = new double[n_points];

		for (int i=0; i<n_points; i++) {
			p_width[i] = 1;
			z_values[i] = layer_set.getLayer(p_layer[i]).getZ();
		}

		return Pipe.makeTube(p[0], p[1], z_values, p_width, resample, parallels, cal);
	}

	synchronized private Object[] getTransformedData() {
		final int n_points = this.n_points;
		final double[][] p = transformPoints(this.p, n_points);
		return new Object[]{p};
	}

	public boolean intersects(final Area area, final double z_first, final double z_last) {
		if (-1 == n_points) setupForDisplay();
		for (int i=0; i<n_points; i++) {
			final double z = layer_set.getLayer(p_layer[i]).getZ();
			if (z < z_first || z > z_last) continue;
			if (area.contains(p[0][i], p[1][i])) return true;
		}
		return false;
	}

	/** Returns a non-calibrated VectorString3D. */
	synchronized public VectorString3D asVectorString3D() {
		// local pointers, since they may be transformed
		int n_points = this.n_points;
		double[][] p = this.p;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			n_points = p[0].length;
		}
		double[] z_values = new double[n_points];
		for (int i=0; i<n_points; i++) {
			z_values[i] = layer_set.getLayer(p_layer[i]).getZ();
		}

		final double[] px = p[0];
		final double[] py = p[1];
		final double[] pz = z_values;
		VectorString3D vs = null;
		try {
			vs = new VectorString3D(px, py, pz, false);
		} catch (Exception e) { IJError.print(e); }
		return vs;
	}

	public String getInfo() {
		if (-1 == n_points) setupForDisplay(); //reload
		// measure length
		double len = 0;
		if (n_points > 1) {
			VectorString3D vs = asVectorString3D();
			vs.calibrate(this.layer_set.getCalibration());
			len = vs.computeLength(); // no resampling
		}
		return new StringBuffer("Length: ").append(Utils.cutNumber(len, 2, true)).append(' ').append(this.layer_set.getCalibration().getUnits()).append('\n').toString();
	}
}