package com.leo.orgadder;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.ListSelectionModel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.leo.orgadder.RsrcHandler.RsrcEntry;

public class MainAL implements ActionListener, ListSelectionListener {

	public static final String RSRC_ORG_TYPE = "ORG";
	public static final int RSRC_LANG_ID = 1041; // Language: Japanese, Sub-Language: Default

	public static final FileNameExtensionFilter FF_EXE = new FileNameExtensionFilter("Executables (*.exe)", "exe");
	public static final FileNameExtensionFilter FF_ORG = new FileNameExtensionFilter("Orgayna music files (*.org)",
			"org");

	public static final String AC_LOAD = "load";
	public static final String AC_LOAD_LAST = "load_last";
	public static final String AC_ADD = "add";
	public static final String AC_REMOVE = "remove";
	public static final String AC_EDIT = "edit";
	public static final String AC_REPLACE = "replace";
	public static final String AC_EXTRACT = "extract";
	public static final String AC_SAVE = "save";
	public static final String AC_SAVE_AS = "save_as";

	private static MainAL singleton;

	public static MainAL initialize(JFrame window) {
		return (singleton = new MainAL(window));
	}

	public static MainAL get() {
		return singleton;
	}

	private static JFrame window;
	private static int currentOrg;
	private static Thread repaintThread;
	private static AtomicBoolean keepRepainting;

	private MainAL(JFrame window) {
		MainAL.window = window;
		orgList = new LinkedList<>();
		orgListComp = new JList<>();
		createOrgListModel();
		orgListComp.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		orgListComp.addListSelectionListener(this);
		orgListComp.setEnabled(false);
		enableOnInit = new LinkedList<>();
	}

	public static synchronized void startRepainting() {
		if (repaintThread != null && repaintThread.isAlive())
			return;
		else
			repaintThread = new Thread(() -> {
				while (keepRepainting.get()) {
					repaint();
					try {
						Thread.sleep(10);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}, "repaint");
		if (keepRepainting == null)
			keepRepainting = new AtomicBoolean(true);
		else
			keepRepainting.set(true);
		repaintThread.start();
	}

	public static synchronized void stopRepainting() {
		if (repaintThread == null)
			return;
		keepRepainting.set(false);
		try {
			repaintThread.join();
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}
	}

	public static File srcFile;
	public static PEFile peData;
	public static RsrcHandler rsrcHandler;
	private static PEFile.Section rsrcSec;
	public static boolean modified;
	public static List<String> orgList;
	public static JList<String> orgListComp;
	public static DefaultListModel<String> orgListCompModel;
	public static List<JComponent> enableOnInit;
	public static JComponent loadLastComp;

	public static void repaint() {
		if (window == null)
			return;
		window.repaint();
	}

	public static boolean promptSaveIfModified() {
		if (!modified)
			return false;
		int sel = JOptionPane.showConfirmDialog(window,
				"Executable has been modified since last save!\nSave executable?", "Unsaved changes detected",
				JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		switch (sel) {
		case JOptionPane.CANCEL_OPTION:
			return true;
		case JOptionPane.YES_OPTION:
			saveOrgList();
			break;
		default:
		case JOptionPane.NO_OPTION:
			break;
		}
		return false;
	}

	public static void createOrgListModel() {
		orgListCompModel = new DefaultListModel<String>();
		for (String cmd : orgList)
			orgListCompModel.addElement(cmd);
		orgListComp.setModel(orgListCompModel);
		orgListComp.setSelectedIndex(currentOrg);
		orgListComp.ensureIndexIsVisible(currentOrg);
	}

	public static boolean loadOrgList() {
		try {
			FileInputStream inStream = new FileInputStream(srcFile);
			FileChannel chan = inStream.getChannel();
			long l = chan.size();
			if (l > 0x7FFFFFFF) {
				inStream.close();
				throw new IOException("Too big!");
			}
			ByteBuffer bb = ByteBuffer.allocate((int) l);
			if (chan.read(bb) != l) {
				inStream.close();
				throw new IOException("Didn't read whole file.");
			}
			inStream.close();
			peData = new PEFile(bb, 0x1000);
		} catch (IOException e) {
			Main.LOGGER.error("Failed to read from file " + srcFile.getAbsolutePath(), e);
			JOptionPane.showMessageDialog(window, "Couldn't read from file:\n" + srcFile.getAbsolutePath(), "Read fail",
					JOptionPane.ERROR_MESSAGE);
			return false;
		}
		int rsrcSecID = peData.getResourcesIndex();
		if (rsrcSecID == -1) {
			JOptionPane.showMessageDialog(window, "Resources section could not be found!", "Read EXE failure",
					JOptionPane.ERROR_MESSAGE);
			return false;
		}
		rsrcSec = peData.sections.get(rsrcSecID);
		rsrcSec.shiftResourceContents(-rsrcSec.virtualAddrRelative);
		rsrcHandler = new RsrcHandler(rsrcSec);
		rsrcSec.shiftResourceContents(rsrcSec.virtualAddrRelative);
		// DEBUG: dump .rsrc contents
		Main.LOGGER.trace("BEGIN .RSRC DUMP (" + rsrcHandler.root.entries.size() + " entries)\n"
				+ dumpResources(0, rsrcHandler.root.entries) + "END .RSRC DUMP");
		// sanity check .rsrc
		RsrcEntry orgDir = rsrcHandler.root.getSubEntry("ORG");
		if (orgDir == null) {
			JOptionPane.showMessageDialog(window, "ORG directory could not be found!",
					".rsrc section sanity check failure", JOptionPane.ERROR_MESSAGE);
			return false;
		}
		boolean sane = true;
		for (RsrcEntry orgEntry : orgDir.entries) {
			if (!orgEntry.isDirectory()) {
				JOptionPane.showMessageDialog(window, "ORG directory contains direct data entry!",
						".rsrc section sanity check failure", JOptionPane.ERROR_MESSAGE);
				sane = false;
				break;
			}
			if (orgEntry.name == null) {
				JOptionPane.showMessageDialog(window, "ORG entry ID " + orgEntry.id + " should be named!",
						".rsrc section sanity check failure", JOptionPane.ERROR_MESSAGE);
				sane = false;
				break;
			}
			if (orgEntry.entries.size() != 1) {
				JOptionPane.showMessageDialog(window,
						"ORG entry \"" + orgEntry.name + "\" doesn't contain one data entry!",
						".rsrc section sanity check failure", JOptionPane.ERROR_MESSAGE);
				sane = false;
				break;
			}
		}
		if (!sane)
			return false;
		if (ONTHandler.readOrSetup(peData)) {
			modified = true;
			JOptionPane.showMessageDialog(window, "Successfully initialized ORG name table section.", "ONT initialized",
					JOptionPane.INFORMATION_MESSAGE);
		} else
			modified = false;
		Vector<String> orgNames = ONTHandler.getOrgNames();
		orgList = new LinkedList<>();
		for (int i = 0; i < orgNames.size(); i++)
			orgList.add(num2TSCParam(i) + " - " + orgNames.get(i));
		createOrgListModel();
		orgListComp.setEnabled(true);
		orgListComp.setSelectedValue(MainAL.orgList.get(0), true);
		if (enableOnInit != null)
			for (JComponent c : enableOnInit)
				c.setEnabled(true);
		return true;
	}

	private static String dumpResources(int indent, List<RsrcHandler.RsrcEntry> entries) {
		String out = "";
		for (RsrcHandler.RsrcEntry entry : entries) {
			String name = entry.name;
			if (name == null)
				name = "ID " + Integer.toUnsignedString(entry.id);
			else
				name = "\"" + name + "\"";
			out += rsrcDumpIndent(indent) + "Directory " + name + ", " + entries.size() + " entries\n";
			if (entry.data == null)
				out += dumpResources(indent + 1, entry.entries);
			else {
				name = entry.name;
				if (name == null)
					name = "ID " + Integer.toUnsignedString(entry.id);
				else
					name = "\"" + name + "\"";
				out += rsrcDumpIndent(indent + 1) + "Data Entry " + name + ", size of 0x"
						+ Integer.toHexString(entry.data.length).toUpperCase() + " bytes\n";
			}
		}
		return out;
	}

	private static String rsrcDumpIndent(int indent) {
		String out = "";
		if (indent > 0) {
			indent--;
			String indentStr = ">";
			if (indent > 0)
				indentStr = new String(new char[indent]).replace('\0', ' ') + indentStr;
			out = indentStr + out;
		}
		return out;
	}

	public static boolean saveOrgListAs() {
		File saveFile = DialogUtil.openFileDialog(true, window, "Save EXE", FF_EXE, srcFile);
		if (saveFile == null)
			return true;
		srcFile = saveFile;
		return saveOrgList0();
	}

	public static boolean saveOrgList() {
		if (srcFile == null)
			return saveOrgListAs();
		return saveOrgList0();
	}

	private static boolean saveOrgList0() {
		peData.sections.remove(rsrcSec);
		rsrcHandler.write(rsrcSec);
		peData.malloc(rsrcSec);
		int rsrcSecRVA = rsrcSec.virtualAddrRelative;
		rsrcSec.shiftResourceContents(rsrcSecRVA);
		peData.setOptionalHeaderInt(0x70, rsrcSecRVA);
		Vector<String> orgNames = ONTHandler.getOrgNames();
		orgNames.clear();
		for (String name : orgList)
			orgNames.add(name.substring(7));
		byte[] b = ONTHandler.write(peData);
		try {
			FileOutputStream oStream = new FileOutputStream(srcFile);
			oStream.write(b);
			oStream.close();
		} catch (IOException e) {
			Main.LOGGER.error("Failed to write to file " + srcFile.getAbsolutePath(), e);
			JOptionPane.showMessageDialog(window, "Couldn't write to file:\n" + srcFile.getAbsolutePath(), "Write fail",
					JOptionPane.ERROR_MESSAGE);
			return false;
		}
		modified = false;
		return true;
	}

	public static String num2TSCParam(long p) {
		String ret = "";
		for (int j = 0; j < 3; j++) {
			ret = (char) ('0' + p % 10) + ret;
			p /= 10;
		}
		ret = (char) ('0' + p) + ret;
		return ret;
	}

	public static byte[] getOrgFile(String title) {
		File orgFile = DialogUtil.openFileDialog(false, window, title, FF_ORG, srcFile);
		if (orgFile == null)
			return null;
		byte[] orgData = null;
		try {
			FileInputStream orgIn = new FileInputStream(orgFile);
			orgData = new byte[orgIn.available()];
			if (orgIn.read(orgData) != orgData.length) {
				orgIn.close();
				throw new IOException("Could not read whole file.");
			}
			orgIn.close();
		} catch (IOException e) {
			Main.LOGGER.error("Error while reading ORG file", e);
			JOptionPane.showMessageDialog(window, "Read ORG file failure",
					"Could not read ORG file\n\"" + orgFile.getAbsolutePath() + "\"!", JOptionPane.ERROR_MESSAGE);
			return null;
		}
		return orgData;
	}

	public static byte[] getOrgData(String name) {
		RsrcEntry orgDir = rsrcHandler.root.getSubEntry("ORG");
		RsrcEntry orgEntry = orgDir.getSubEntry(name);
		if (orgEntry == null)
			return null;
		return orgEntry.entries.get(0).data;
	}

	public static void setOrgData(String name, byte[] org) {
		RsrcEntry orgDir = rsrcHandler.root.getSubEntry("ORG");
		RsrcEntry orgEntry = orgDir.getSubEntry(name);
		if (orgEntry == null) {
			orgEntry = new RsrcEntry();
			orgEntry.parent = orgDir;
			orgEntry.name = name;
			orgEntry.entries = new LinkedList<>();
			orgDir.entries.add(orgEntry);
		} else if (org == null) {
			orgDir.entries.remove(orgEntry);
			return;
		}
		orgEntry.entries.clear();
		RsrcEntry orgData = new RsrcEntry();
		orgData.parent = orgEntry;
		orgData.id = 1041;
		orgData.data = org;
		orgEntry.entries.add(orgData);
	}

	@Override
	public void actionPerformed(ActionEvent ae) {
		boolean hit;
		byte[] orgData;
		String newName;
		switch (ae.getActionCommand()) {
		case AC_LOAD:
			if (promptSaveIfModified())
				break;
			File dir = srcFile;
			if (dir == null || !dir.exists())
				dir = new File(Config.get(Config.KEY_LAST_EXE, System.getProperty("user.dir")));
			File newSrc = DialogUtil.openFileDialog(false, window, "Open EXE", FF_EXE, dir);
			if (newSrc == null)
				break;
			srcFile = newSrc;
			if (!loadOrgList())
				break;
			Config.set(Config.KEY_LAST_EXE, srcFile.getAbsolutePath());
			if (loadLastComp != null)
				loadLastComp.setEnabled(true);
			break;
		case AC_LOAD_LAST:
			if (promptSaveIfModified())
				break;
			File last = new File(Config.get(Config.KEY_LAST_EXE));
			if (!last.exists()) {
				JOptionPane.showMessageDialog(window, "The last loaded EXE doesn't exist anymore!",
						"Couldn't load last EXE", JOptionPane.ERROR_MESSAGE);
				break;
			}
			srcFile = last;
			if (!loadOrgList())
				break;
			Config.set(Config.KEY_LAST_EXE, srcFile.getAbsolutePath());
			if (loadLastComp != null)
				loadLastComp.setEnabled(true);
			break;
		case AC_ADD:
			orgData = getOrgFile("Select ORG file to add");
			if (orgData == null)
				return;
			newName = DialogUtil.showInputDialog(window, "Enter new ORG name:", "Add ORG", "NEWDATA");
			if (newName == null || newName.isEmpty())
				break;
			hit = false;
			for (int i = 0; i < orgList.size(); i++) {
				String otherName = orgList.get(i).substring(7);
				if (newName.equalsIgnoreCase(otherName)) {
					JOptionPane.showMessageDialog(window, "The name \"" + newName + "\" already exists!",
							"Add ORG failure", JOptionPane.ERROR_MESSAGE);
					hit = true;
					break;
				}
			}
			if (hit)
				break;
			setOrgData(newName, orgData);
			currentOrg = orgList.size();
			orgList.add(currentOrg, num2TSCParam(currentOrg) + " - " + newName);
			createOrgListModel();
			modified = true;
			break;
		case AC_REMOVE:
			if (currentOrg == 0) {
				JOptionPane.showMessageDialog(window, "Can't remove the very first ORG!", "Cant't remove ORG",
						JOptionPane.ERROR_MESSAGE);
				break;
			}
			setOrgData(orgList.remove(currentOrg).substring(7), null);
			currentOrg--;
			if (currentOrg < 0)
				currentOrg = 0;
			createOrgListModel();
			modified = true;
			break;
		case AC_EDIT:
			String oldName = orgList.get(currentOrg);
			oldName = oldName.substring(7);
			int oldHash = oldName.hashCode();
			newName = DialogUtil.showInputDialog(window, "Enter new ORG name:", "Edit ORG", oldName);
			if (newName == null || newName.isEmpty())
				break;
			String actualOrgName = num2TSCParam(currentOrg) + " - " + newName;
			if (oldHash != actualOrgName.hashCode()) {
				hit = false;
				for (int i = 0; i < orgList.size(); i++) {
					String otherName = orgList.get(i).substring(7);
					if (newName.equalsIgnoreCase(otherName)) {
						JOptionPane.showMessageDialog(window, "The name \"" + newName + "\" already exists!",
								"Edit ORG failure", JOptionPane.ERROR_MESSAGE);
						hit = true;
						break;
					}
				}
				if (hit)
					break;
				RsrcEntry orgDir = rsrcHandler.root.getSubEntry("ORG");
				RsrcEntry orgEntry = orgDir.getSubEntry(oldName);
				if (orgEntry != null)
					orgEntry.name = newName;
				orgList.set(currentOrg, actualOrgName);
				createOrgListModel();
				modified = true;
			}
			break;
		case AC_REPLACE:
			orgData = getOrgFile("Select ORG file to replace with");
			if (orgData == null)
				break;
			setOrgData(orgList.get(currentOrg).substring(7), orgData);
			modified = true;
			break;
		case AC_EXTRACT:
			orgData = getOrgData(orgList.get(currentOrg).substring(7));
			if (orgData == null) {
				JOptionPane.showMessageDialog(window,
						"The resource data for this ORG does not exist!\nPlease use the \"REPLACE\" option to add resource data for this ORG.",
						"ORG resource does not exist!", JOptionPane.ERROR_MESSAGE);
				break;
			}
			File outFile = DialogUtil.openFileDialog(true, window, "Save ORG file", FF_ORG, srcFile);
			if (outFile == null)
				break;
			try {
				FileOutputStream outS = new FileOutputStream(outFile);
				outS.write(orgData);
				outS.close();
			} catch (IOException e) {
				Main.LOGGER.error("Error while writing ORG file", e);
				JOptionPane.showMessageDialog(window, "Write ORG file failure",
						"Could not write ORG file\n\"" + outFile.getAbsolutePath() + "\"!", JOptionPane.ERROR_MESSAGE);
			}
			break;
		case AC_SAVE:
			saveOrgList();
			break;
		case AC_SAVE_AS:
			saveOrgListAs();
			break;
		}
	}

	@Override
	public void valueChanged(ListSelectionEvent e) {
		if (e.getSource().equals(orgListComp)) {
			int sel = orgListComp.getSelectedIndex();
			if (sel < 0)
				return;
			currentOrg = sel;
		}
	}

}
