package com.leo.orgadder;

import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.Collection;

import javax.swing.BoxLayout;
import javax.swing.JFileChooser;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

public class DialogUtil {

	public static int showCustomConfirmDialog(Component parentComponent, Object message, String title, String[] options,
			int messageType) {
		return JOptionPane.showOptionDialog(parentComponent, message, title, JOptionPane.DEFAULT_OPTION, messageType,
				null, options, null);
	}

	public static File openFileDialog(boolean openOrSave, Component parent, String title,
			FileNameExtensionFilter filter, File dir) {
		JFileChooser fc = new JFileChooser();
		fc.setMultiSelectionEnabled(false);
		fc.setAcceptAllFileFilterUsed(false);
		fc.setDialogTitle(title);
		fc.setFileFilter(filter);
		if (dir == null)
			dir = new File(System.getProperty("user.dir"));
		fc.setCurrentDirectory(dir);
		if (openOrSave) {
			int ret = fc.showSaveDialog(parent);
			if (ret == JFileChooser.APPROVE_OPTION) {
				File sel = fc.getSelectedFile();
				String selName = sel.getName();
				String ext = filter.getExtensions()[0];
				if (!selName.contains(".")
						|| !selName.substring(selName.lastIndexOf(".") + 1, selName.length()).equalsIgnoreCase(ext)) {
					selName += "." + ext;
					sel = new File(sel.getParentFile().getPath() + "/" + selName);
				}
				if (sel.exists()) {
					int confirm = JOptionPane.showConfirmDialog(parent,
							"File \"" + sel.getAbsolutePath() + "\"\nalready exists! Overwrite it?",
							"Overwrite selected file?", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
					if (confirm != JOptionPane.OK_OPTION)
						return null;
				}
				return sel;
			}
		} else {
			int ret = fc.showOpenDialog(parent);
			if (ret == JFileChooser.APPROVE_OPTION)
				return fc.getSelectedFile();
		}
		return null;
	}

	public static String showSelectionDialog(Component parent, String title, String[] selections,
			String initialSelection, boolean canCancel) {
		JList<String> list = new JList<String>(selections);
		JScrollPane scrollpane = new JScrollPane();
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));
		panel.add(scrollpane);
		scrollpane.getViewport().add(list);
		list.setSelectedValue(initialSelection, true);
		String ret = null;
		final boolean[] dc = new boolean[1];
		list.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() > 1) {
					dc[0] = true;
					SwingUtilities.windowForComponent(list).dispose();
				}
			}
		});
		int msg = JOptionPane.showConfirmDialog(parent, panel, title, JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE);
		if (dc[0] || msg == JOptionPane.OK_OPTION)
			ret = list.getSelectedValue();
		return ret;
	}

	public static String showSelectionDialog(Component parent, String title, Collection<String> selections,
			String initialSelection, boolean canCancel) {
		return showSelectionDialog(parent, title, selections.toArray(new String[] {}), initialSelection, canCancel);
	}

	public static String showInputDialog(Component parent, Object message, String title, String initialSelectionValue) {
		Object retObj = JOptionPane.showInputDialog(parent, message, title, JOptionPane.PLAIN_MESSAGE, null, null,
				initialSelectionValue);
		;
		if (retObj == null)
			return null;
		if (!(retObj instanceof String))
			throw new RuntimeException("This doesn't make any sense...");
		return (String) retObj;
	}

}
