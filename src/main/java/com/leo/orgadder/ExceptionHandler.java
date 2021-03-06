package com.leo.orgadder;

import java.lang.Thread.UncaughtExceptionHandler;

import javax.swing.JOptionPane;

public class ExceptionHandler implements UncaughtExceptionHandler {

	@Override
	public void uncaughtException(Thread t, Throwable e) {
		Main.LOGGER.error("Uncaught exception in thread \"" + t.getName() + "\":", e);
		JOptionPane.showMessageDialog(null,
				"An uncaught exception has occured!\nPlease report this error here:\n" + Main.ISSUES_SITE,
				"Uncaught exception!", JOptionPane.ERROR_MESSAGE);
		System.exit(1);
	}

}
