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

public class MainAL implements ActionListener, ListSelectionListener {

	public static final FileNameExtensionFilter FF_EXE = new FileNameExtensionFilter("Executables (*.exe)", "exe");

	public static final String AC_LOAD = "load";
	public static final String AC_LOAD_LAST = "load_last";
	public static final String AC_ADD = "add";
	public static final String AC_REMOVE = "remove";
	public static final String AC_EDIT = "edit";
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
		if (ONTHandler.readOrSetup(peData)) {
			modified = true;
			JOptionPane.showMessageDialog(window, "Successfully initialized ORG name table section.", "ONT initialized",
					JOptionPane.INFORMATION_MESSAGE);
		} else
			modified = false;
		orgList = new LinkedList<>(ONTHandler.getOrgNames());
		createOrgListModel();
		orgListComp.setEnabled(true);
		orgListComp.setSelectedValue(MainAL.orgList.get(0), true);
		if (enableOnInit != null)
			for (JComponent c : enableOnInit)
				c.setEnabled(true);
		return true;
	}

	public static boolean saveOrgListAs() {
		File saveFile = DialogUtil.openFileDialog(true, window, "Save EXE", FF_EXE, srcFile);
		if (saveFile == null)
			if (srcFile == null)
				return true;
		saveFile = srcFile;
		return saveOrgList0();
	}

	public static boolean saveOrgList() {
		if (srcFile == null)
			return saveOrgListAs();
		return saveOrgList0();
	}

	private static boolean saveOrgList0() {
		Vector<String> orgNames = ONTHandler.getOrgNames();
		orgNames.clear();
		for (String name : orgList)
			orgNames.add(name);
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

	@Override
	public void actionPerformed(ActionEvent ae) {
		boolean hit;
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
			String newName = DialogUtil.showInputDialog(window, "Enter new ORG name:", "Add ORG", "NEWDATA");
			if (newName == null)
				break;
			hit = false;
			for (int i = 0; i < orgList.size(); i++) {
				if (i == currentOrg)
					continue;
				String otherName = orgList.get(i);
				if (newName.equalsIgnoreCase(otherName)) {
					JOptionPane.showMessageDialog(window, "The name \"" + newName + "\" already exists!",
							"Add ORG failure", JOptionPane.ERROR_MESSAGE);
					hit = true;
					break;
				}
			}
			if (hit)
				break;
			currentOrg = orgList.size();
			orgList.add(currentOrg, newName);
			createOrgListModel();
			modified = true;
			break;
		case AC_REMOVE:
			if (currentOrg == 0) {
				JOptionPane.showMessageDialog(window, "Can't remove the very first ORG!", "Cant't remove ORG",
						JOptionPane.ERROR_MESSAGE);
				break;
			}
			orgList.remove(currentOrg);
			if (currentOrg == orgList.size())
				currentOrg--;
			if (currentOrg < 0)
				currentOrg = 0;
			createOrgListModel();
			modified = true;
			break;
		case AC_EDIT:
			String orgName = orgList.get(currentOrg);
			int oldHash = orgName.hashCode();
			orgName = DialogUtil.showInputDialog(window, "Enter new ORG name:", "Edit ORG", orgName);
			if (orgName == null)
				break;
			if (oldHash != orgName.hashCode()) {
				hit = false;
				for (int i = 0; i < orgList.size(); i++) {
					if (i == currentOrg)
						continue;
					String otherName = orgList.get(i);
					if (orgName.equalsIgnoreCase(otherName)) {
						JOptionPane.showMessageDialog(window, "The name \"" + orgName + "\" already exists!",
								"Edit ORG failure", JOptionPane.ERROR_MESSAGE);
						hit = true;
						break;
					}
				}
				if (hit)
					break;
				orgList.set(currentOrg, orgName);
				createOrgListModel();
				modified = true;
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
