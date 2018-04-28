package com.leo.orgadder;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.LinkedList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Main {

	public static final Logger LOGGER = LogManager.getLogger("OrgAdder");

	public static final Version VERSION = new Version("1.0.4");
	public static final String UPDATE_CHECK_SITE = "https://raw.githubusercontent.com/Leo40Git/OrgAdder/master/.version";
	public static final String DOWNLOAD_SITE = "https://github.com/Leo40Git/OrgAdder/releases/latest/";
	public static final String ISSUES_SITE = "https://github.com/Leo40Git/OrgAdder/issues";

	static class ConfirmCloseWindowListener extends WindowAdapter {

		@Override
		public void windowClosing(WindowEvent e) {
			boolean cancel = MainAL.promptSaveIfModified();
			if (cancel)
				return;
			MainAL.stopRepainting();
			System.exit(0);
		}

	}

	public static final String GUIDE_STR = "Okay listen up it's pretty simple\n" + "1. Load your EXE with OrgAdder\n"
			+ "2. Click on ORG+ to add a new entry\n" + "3. Type in a name. Remember this name, you're gonna need it\n"
			+ "4. Press \"OK\", and save your EXE\n" + "5. Now, open your EXE with Resource Hacker\n"
			+ "6. Open the \"Action\" menu and click on \"Add Single Binary or Image Resource ...\"\n"
			+ "7. Click on \"Select File ...\" and select your ORG file\n"
			+ "8. In the \"Resource Type\" field, type in \"ORG\"\n"
			+ "9. In the \"Resource Name\" field, type in the name you entered in OrgAdder\n"
			+ "10. In the \"Resource Language\" field, type in 1041 (Japanese, Default)\n"
			+ "10b. If the \"Resource Language\" field is disabled, skip this step and follow step 11b\n"
			+ "11. Click on \"Add Resource\". You should see your new ORG appear in the resource explorer!\n"
			+ "11b. Right click on the ORG, and click on \"Change Language for this Resource ...\". Type 1041 into the \"Lang. ID\" field\n"
			+ "12. Finally, save your EXE within Resource Hacker\n" + "Your ORG is now ready to be used in-game!";

	public static final String AC_GUIDE = "guide";
	public static final String AC_ABOUT = "about";
	public static final String AC_UPDATE = "update";

	static class HelpActionListener implements ActionListener {

		@Override
		public void actionPerformed(ActionEvent e) {
			switch (e.getActionCommand()) {
			case AC_GUIDE:
				JOptionPane.showMessageDialog(window, GUIDE_STR, "Guide", JOptionPane.PLAIN_MESSAGE);
				break;
			case AC_ABOUT:
				if (aboutIcon == null)
					aboutIcon = new ImageIcon(appIcons.get(APPICON_32), "About");
				JOptionPane.showMessageDialog(window, "OrgAdder version " + VERSION + "\nMade by Leo",
						"About OrgAdder v" + VERSION, JOptionPane.INFORMATION_MESSAGE, aboutIcon);
				break;
			case AC_UPDATE:
				SwingUtilities.invokeLater(() -> {
					updateCheck(true, true);
				});
				break;
			default:
				break;
			}
		}

	}

	public static List<Image> appIcons;
	public static ImageIcon aboutIcon;

	public static void initAppIcons() throws IOException {
		appIcons = new LinkedList<>();
		final String[] sizes = new String[] { "16", "32", "64" };
		for (String size : sizes)
			appIcons.add(ImageIO.read(Main.class.getResourceAsStream("/appicon" + size + ".png")));
	}

	public static final int APPICON_16 = 0;
	public static final int APPICON_32 = 1;
	public static final int APPICON_64 = 2;

	private static JFrame window;

	public static JFrame getWindow() {
		return window;
	}

	public static void main(String[] args) {
		if (GraphicsEnvironment.isHeadless()) {
			System.out.println("Headless mode is enabled!\nOrgAdder cannot run in headless mode!");
			System.exit(0);
		}
		Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler());
		final String nolaf = "nolaf";
		if (new File(System.getProperty("user.dir") + "/" + nolaf).exists())
			LOGGER.info("No L&F file detected, skipping setting Look & Feel");
		else
			try {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			} catch (ClassNotFoundException | InstantiationException | IllegalAccessException
					| UnsupportedLookAndFeelException e) {
				LOGGER.fatal("Error while setting system look and feel", e);
				JOptionPane.showMessageDialog(null, "Could not set Look & Feel!\nPlease add a file named \"" + nolaf
						+ "\" (all lowercase, no extension) to the application folder, and then restart the application.",
						"Could not set Look & Feel", JOptionPane.ERROR_MESSAGE);
				System.exit(1);
			}
		Config.init();
		LoadFrame loadFrame;
		final String skipuc = "skipuc";
		boolean skipucF = new File(System.getProperty("user.dir") + "/" + skipuc).exists();
		boolean skipucR = Config.getBoolean(Config.KEY_SKIP_UPDATE_CHECK, false);
		if (skipucR) {
			Config.setBoolean(Config.KEY_SKIP_UPDATE_CHECK, false);
			skipucF = skipucR;
		}
		try {
			initAppIcons();
		} catch (IOException e1) {
			resourceError(e1);
		}
		if (skipucF) {
			LOGGER.info("Update check: skip file detected, skipping");
			loadFrame = new LoadFrame();
		} else {
			loadFrame = updateCheck(false, false);
		}
		SwingUtilities.invokeLater(() -> {
			loadFrame.setLoadString("Loading...");
			loadFrame.repaint();
		});
		SwingUtilities.invokeLater(() -> {
			window = new JFrame();
			window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
			window.addWindowListener(new ConfirmCloseWindowListener());
			window.setResizable(false);
			MainAL.initialize(window);
			window.setJMenuBar(makeMenuBar());
			JScrollPane scrollpane = new JScrollPane();
			JPanel panel = new JPanel();
			Font baseFnt = panel.getFont();
			monoFont = new Font(Font.MONOSPACED, baseFnt.getStyle(), baseFnt.getSize());
			MainAL.orgListComp.setFont(monoFont);
			panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
			panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
			panel.add(scrollpane);
			scrollpane.getViewport().add(MainAL.orgListComp);
			JPanel btnPanel = new JPanel();
			btnPanel.add(makeButton("ADD ORG", MainAL.AC_ADD));
			btnPanel.add(makeButton("DEL ORG", MainAL.AC_REMOVE));
			btnPanel.add(makeButton("REN ORG", MainAL.AC_EDIT));
			btnPanel.add(makeButton("REPLACE", MainAL.AC_REPLACE));
			btnPanel.add(makeButton("EXTRACT", MainAL.AC_EXTRACT));
			panel.add(btnPanel);
			window.add(panel);
			window.pack();
			Dimension scrollpaneSize = scrollpane.getSize();
			int heightIncrease = scrollpaneSize.height;
			scrollpaneSize.height *= 2;
			scrollpane.setPreferredSize(scrollpaneSize);
			scrollpane.setMinimumSize(scrollpaneSize);
			scrollpane.setMaximumSize(scrollpaneSize);
			Dimension windowSize = window.getSize();
			windowSize.height += heightIncrease;
			window.setSize(windowSize);
			window.setLocationRelativeTo(null);
			window.setTitle("OrgAdder v" + VERSION);
			window.setIconImages(appIcons);
			window.setVisible(true);
			window.requestFocus();
			loadFrame.dispose();
		});
	}

	private static Font monoFont;

	private static JMenuBar makeMenuBar() {
		JMenuBar mb = new JMenuBar();
		JMenu m = new JMenu("File");
		JMenuItem mi = makeMenuItem("Load EXE", MainAL.AC_LOAD, false);
		mi.setAccelerator(KeyStroke.getKeyStroke("control O"));
		m.add(mi);
		mi = makeMenuItem("Load Last EXE", MainAL.AC_LOAD_LAST, false);
		MainAL.loadLastComp = mi;
		if (Config.get(Config.KEY_LAST_EXE) == null)
			mi.setEnabled(false);
		mi.setAccelerator(KeyStroke.getKeyStroke("control shift O"));
		m.add(mi);
		m.addSeparator();
		mi = makeMenuItem("Save EXE", MainAL.AC_SAVE, true);
		mi.setAccelerator(KeyStroke.getKeyStroke("control S"));
		m.add(mi);
		mi = makeMenuItem("Save EXE As...", MainAL.AC_SAVE_AS, true);
		mi.setAccelerator(KeyStroke.getKeyStroke("control shift S"));
		m.add(mi);
		mb.add(m);
		HelpActionListener helpAS = new HelpActionListener();
		m = new JMenu("Help");
		m.add(makeMenuItem("Guide", helpAS, AC_GUIDE));
		m.addSeparator();
		m.add(makeMenuItem("About OrgAdder", helpAS, AC_ABOUT));
		m.add(makeMenuItem("Check for Updates", helpAS, AC_UPDATE));
		mb.add(m);
		return mb;
	}

	private static JMenuItem makeMenuItem(String label, ActionListener as, String ac) {
		JMenuItem mi = new JMenuItem(label);
		mi.setActionCommand(ac);
		mi.addActionListener(as);
		return mi;
	}

	private static JMenuItem makeMenuItem(String label, String ac, boolean disableUntilInit) {
		JMenuItem mi = makeMenuItem(label, MainAL.get(), ac);
		if (disableUntilInit) {
			mi.setEnabled(false);
			MainAL.enableOnInit.add(mi);
		}
		return mi;
	}

	private static JButton makeButton(String label, String ac) {
		JButton btn = new JButton(label);
		btn.setFont(monoFont);
		btn.setActionCommand(ac);
		btn.addActionListener(MainAL.get());
		btn.setEnabled(false);
		MainAL.enableOnInit.add(btn);
		return btn;
	}

	public static void resourceError(Throwable e) {
		LOGGER.fatal("Error while loading resources", e);
		JOptionPane.showMessageDialog(null,
				"Could not load resources:" + e + "\nPlease report this error here:\n" + ISSUES_SITE,
				"Could not load resources!", JOptionPane.ERROR_MESSAGE);
		System.exit(1);
	}

	public static void downloadFile(String url, File dest) throws IOException {
		URL site = new URL(url);
		try (InputStream siteIn = site.openStream();
				ReadableByteChannel rbc = Channels.newChannel(siteIn);
				FileOutputStream out = new FileOutputStream(dest)) {
			out.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
		}
	}

	public static boolean browseTo(String url) throws URISyntaxException, IOException {
		URI dlSite = new URI(url);
		if (Desktop.isDesktopSupported())
			Desktop.getDesktop().browse(dlSite);
		else
			return false;
		return true;
	}

	public static LoadFrame updateCheck(boolean disposeOfLoadFrame, boolean showUpToDate) {
		LoadFrame loadFrame = new LoadFrame();
		File verFile = new File(System.getProperty("user.dir") + "/temp.version");
		LOGGER.info("Update check: starting");
		try {
			downloadFile(UPDATE_CHECK_SITE, verFile);
		} catch (IOException e1) {
			LOGGER.error("Update check failed: attempt to download caused exception", e1);
			JOptionPane.showMessageDialog(null, "The update check has failed!\nAre you not connected to the internet?",
					"Update check failed", JOptionPane.ERROR_MESSAGE);
		}
		if (verFile.exists()) {
			LOGGER.info("Update check: reading version");
			try (FileReader fr = new FileReader(verFile); BufferedReader reader = new BufferedReader(fr);) {
				Version check = new Version(reader.readLine());
				if (VERSION.compareTo(check) < 0) {
					LOGGER.info("Update check successful: have update");
					JPanel panel = new JPanel();
					panel.setLayout(new BorderLayout());
					panel.add(new JLabel("A new update is available: " + check), BorderLayout.PAGE_START);
					final String defaultCl = "No changelog provided.";
					String cl = defaultCl;
					while (reader.ready()) {
						if (defaultCl.equals(cl))
							cl = reader.readLine();
						else
							cl += "\n" + reader.readLine();
					}
					JTextArea chglog = new JTextArea(cl);
					chglog.setEditable(false);
					chglog.setPreferredSize(new Dimension(800, 450));
					JScrollPane scrollChglog = new JScrollPane(chglog);
					panel.add(scrollChglog, BorderLayout.CENTER);
					panel.add(
							new JLabel(
									"Click \"Yes\" to go to the download site, click \"No\" to continue to OrgAdder."),
							BorderLayout.PAGE_END);
					int result = JOptionPane.showConfirmDialog(null, panel, "New update!", JOptionPane.YES_NO_OPTION,
							JOptionPane.PLAIN_MESSAGE);
					if (result == JOptionPane.YES_OPTION) {
						if (!browseTo(DOWNLOAD_SITE))
							JOptionPane.showMessageDialog(null,
									"Sadly, we can't browse to the download site for you on this platform. :(\nHead to\n"
											+ DOWNLOAD_SITE + "\nto get the newest update!",
									"Operation not supported...", JOptionPane.ERROR_MESSAGE);
						System.exit(0);
					}
				} else {
					LOGGER.info("Update check successful: up to date");
					if (showUpToDate) {
						JOptionPane.showMessageDialog(null,
								"You are using the most up to date version of OrgAdder! Have fun!", "Up to date!",
								JOptionPane.INFORMATION_MESSAGE);
					}
				}
			} catch (IOException e) {
				LOGGER.error("Update check failed: attempt to read downloaded file caused exception", e);
				JOptionPane.showMessageDialog(null,
						"The update check has failed!\nAn exception occured while reading update check results:\n" + e,
						"Update check failed", JOptionPane.ERROR_MESSAGE);
			} catch (URISyntaxException e1) {
				LOGGER.error("Browse to download site failed: bad URI syntax", e1);
				JOptionPane.showMessageDialog(null, "Failed to browse to the download site...",
						"Well, this is awkward.", JOptionPane.ERROR_MESSAGE);
			} finally {
				verFile.delete();
			}
		} else
			LOGGER.error("Update check failed: downloaded file doesn't exist");
		if (disposeOfLoadFrame) {
			loadFrame.dispose();
			return null;
		}
		return loadFrame;
	}

}
