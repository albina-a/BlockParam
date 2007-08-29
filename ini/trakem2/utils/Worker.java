/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005,2006 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt)

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

package ini.trakem2.utils;

public abstract class Worker extends Thread {
	private String task_name;
	private boolean working = false;
	protected boolean quit = false;
	public Worker(String task_name) {
		this(task_name, false);
	}
	public Worker(String task_name, boolean headless_mode) {
		super((headless_mode ? "Run$_": "") +  "Worker"); // the Run$_ tag is for ImageJ to properly grant it Macro.getOptions()
		this.task_name = task_name;
		setPriority(Thread.NORM_PRIORITY);
	}
	protected void setTaskName(String name) { this.task_name = name; }
	protected void startedWorking() { this.working = true; }
	protected void finishedWorking() { this.working = false; }
	public boolean isWorking() { return working; }
	public String getTaskName() { return task_name; }
	public void quit() { this.quit = true; }
	public boolean hasQuitted() { return this.quit; }
}
