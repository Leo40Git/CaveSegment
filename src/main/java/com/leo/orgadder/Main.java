package com.leo.orgadder;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Main {

	public static final Logger LOGGER = LogManager.getLogger("OrgAdder");

	public static final Version VERSION = new Version("1.0");
	public static final String UPDATE_CHECK_SITE = "https://raw.githubusercontent.com/Leo40Git/OrgAdder/master/.version";
	public static final String DOWNLOAD_SITE = "https://github.com/Leo40Git/OrgAdder/releases/latest/";
	public static final String ISSUES_SITE = "https://github.com/Leo40Git/OrgAdder/issues";

	static class ConfirmCloseWindowListener extends WindowAdapter {

		@Override
		public void windowClosing(WindowEvent e) {
			if (src != null && modified) {
				int sel = JOptionPane.showConfirmDialog(window,
						"Executable has been modified since last save!\nSave executable?", "Unsaved changes detected",
						JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
				if (sel == JOptionPane.CANCEL_OPTION)
					return;
				if (sel == JOptionPane.YES_OPTION) {
					// TODO
				}
			}
			System.exit(0);
		}

	}

	private static JFrame window;

	public static JFrame getWindow() {
		return window;
	}

	private static File src;
	private static PEFile peData;
	private static boolean modified;

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
			window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			final Dimension size = new Dimension(360, 180);
			window.setPreferredSize(size);
			window.setMaximumSize(size);
			window.setMinimumSize(size);
			window.setResizable(false);
			// TODO Add components
			window.setLocationRelativeTo(null);
			window.setTitle("OrgAdder v" + VERSION);
			window.setVisible(true);
			window.requestFocus();
			loadFrame.dispose();
		});
	}

	public static void load(File f) {
		src = f;
		try {
			FileInputStream inStream = new FileInputStream(src);
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
			LOGGER.error("Failed to read from file " + f.getAbsolutePath(), e);
			JOptionPane.showMessageDialog(window, "Couldn't read from file:\n" + f.getAbsolutePath(), "Read fail",
					JOptionPane.ERROR_MESSAGE);
		}
	}

	public static void save(File f) {
		if (src == null || peData == null)
			return;
		byte[] b = ONTHandler.write(peData);
		try {
			FileOutputStream oStream = new FileOutputStream(f);
			oStream.write(b);
			oStream.close();
		} catch (IOException e) {
			LOGGER.error("Failed to write to file " + f.getAbsolutePath(), e);
			JOptionPane.showMessageDialog(window, "Couldn't write to file:\n" + f.getAbsolutePath(), "Write fail",
					JOptionPane.ERROR_MESSAGE);
		}
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
							new JLabel("Click \"Yes\" to go to the download site, click \"No\" to continue to OSTBM."),
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
								"You are using the most up to date version of the OneShot Textbox Maker! Have fun!",
								"Up to date!", JOptionPane.INFORMATION_MESSAGE);
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
