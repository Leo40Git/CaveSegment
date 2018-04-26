package com.leo.cseg;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.Vector;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

public class Main {

	private static PEFile peData;

	public static void main(String[] args) {
		JFileChooser fc = new JFileChooser();
		FileNameExtensionFilter filter = new FileNameExtensionFilter("Cave Story Executable", "exe");
		fc.setFileFilter(filter);
		fc.setCurrentDirectory(new File(System.getProperty("user.dir")));
		int retVal = fc.showOpenDialog(null);
		if (retVal == JFileChooser.APPROVE_OPTION) {
			File selected = fc.getSelectedFile();
			try {
				FileInputStream inStream = new FileInputStream(selected);
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
				// DEBUG: dump section ranges
				// sort the sections so we go by RVA
				LinkedList<PEFile.Section> sectionsSorted = new LinkedList<PEFile.Section>(peData.sections);
				sectionsSorted.sort(sortByRVA);
				for (PEFile.Section s : sectionsSorted) {
					System.out.println(s.decodeTag() + ": " + int2Hex(0x400000 + s.virtualAddrRelative) + " - "
							+ int2Hex(0x400000 + s.virtualAddrRelative + Math.max(s.virtualSize, s.rawData.length)));
				}
				/*
				addEntityListSegment();
				JOptionPane.showMessageDialog(null, "Done .npt");
				*/
				addMusicListSegment();
				JOptionPane.showMessageDialog(null, "Done .onl");
				byte[] b = peData.write();
				FileOutputStream oStream = new FileOutputStream(selected);
				oStream.write(b);
				oStream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		System.exit(0);
	}

	private static final int NPT_RVA = 0x498548;
	private static final int[] NPT_REFS = new int[] { 0x46FA65, 0x46FCF2, 0x46FF78 };

	@SuppressWarnings("unused")
	private static void addEntityListSegment() {
		if (peData.getSectionIndexByTag(".npt") != -1)
			return;
		removeFillerSections();
		int nptSize = 0;
		ByteBuffer listBuf = peData.setupRVAPoint(NPT_RVA - 0x400000);
		while (listBuf.getInt() >= 0x400000)
			nptSize += 4;
		listBuf.flip();
		byte[] list = new byte[nptSize];
		listBuf.get(list);
		PEFile.Section nptSection = new PEFile.Section();
		nptSection.encodeTag(".npt");
		nptSection.rawData = list;
		nptSection.virtualSize = list.length;
		nptSection.metaLinearize = false;
		nptSection.characteristics = PEFile.SECCHR_INITIALIZED_DATA | PEFile.SECCHR_READ;
		peData.malloc(nptSection);
		ByteBuffer buf = ByteBuffer.allocate(4);
		buf.order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(0, nptSection.virtualAddrRelative + 0x400000);
		for (int ref : NPT_REFS)
			patch(buf, ref);
		fixVirtualLayoutGaps();
	}

	private static final int ONL_RVA = 0x4981E8;
	private static final int[] ONL_REFS = { 0x420F17, 0x420F60 };

	private static void addMusicListSegment() {
		if (peData.getSectionIndexByTag(".onl") != -1)
			return;
		removeFillerSections();
		ByteBuffer listBuf = peData.setupRVAPoint(ONL_RVA - 0x400000);
		int onlSize = 0;
		Vector<byte[]> orgNames = new Vector<>();
		int orgNameSize = 0;
		while (true) {
			int orgAddr = listBuf.getInt();
			if (orgAddr < 0x400000)
				// if next address is before image base, it's probably not an address
				// i.e we're overflowing and need to stop
				break;
			onlSize += 4;
			byte[] orgNameTmp = new byte[0x80];
			int index = 0;
			boolean gotTerm = false;
			while (true) {
				ByteBuffer cBuf = read(orgAddr++, 1);
				byte c = cBuf.get(0);
				if (gotTerm && c != 0)
					break;
				else if (c == 0)
					gotTerm = true;
				orgNameTmp[index++] = c;
			}
			byte[] orgName = new byte[index];
			System.arraycopy(orgNameTmp, 0, orgName, 0, index);
			orgNameSize += orgName.length;
			orgNames.add(orgName);
		}
		System.out.println("ORG name pointer table size is " + int2Hex(onlSize));
		System.out.println("Read " + orgNames.size() + " names for a total of " + int2Hex(orgNameSize) + " bytes");
		byte[] data = new byte[onlSize + 4 + orgNameSize];
		// data size: pointers to names + 4 bytes to mark end of pointers + the names
		// themselves
		int usedBytes = 0;
		for (int i = 0; i < orgNames.size(); i++) {
			byte[] name = orgNames.get(i);
			System.arraycopy(name, 0, data, onlSize + 4 + usedBytes, name.length);
			usedBytes += name.length;
		}
		PEFile.Section onlSection = new PEFile.Section();
		onlSection.encodeTag(".onl");
		onlSection.rawData = data;
		onlSection.virtualSize = data.length;
		onlSection.metaLinearize = false;
		onlSection.characteristics = PEFile.SECCHR_INITIALIZED_DATA | PEFile.SECCHR_READ;
		peData.malloc(onlSection);
		// now that we have the .onl segment's RVA, we can fill the start of data with
		// the pointers to the names
		ByteBuffer dataBuf = ByteBuffer.wrap(data);
		dataBuf.order(ByteOrder.LITTLE_ENDIAN);
		final int onlNewRVA = onlSection.virtualAddrRelative + 0x400000;
		int nameRVA = onlNewRVA + onlSize + 4;
		for (int i = 0; i < orgNames.size(); i++) {
			dataBuf.putInt(nameRVA);
			nameRVA += orgNames.get(i).length;
		}
		ByteBuffer buf = ByteBuffer.allocate(4);
		buf.order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(0, onlNewRVA);
		for (int ref : ONL_REFS)
			patch(buf, ref);
		fixVirtualLayoutGaps();
	}

	private static final Comparator<PEFile.Section> sortByRVA = new Comparator<PEFile.Section>() {

		@Override
		public int compare(PEFile.Section o1, PEFile.Section o2) {
			int rva1 = PEFile.uCompare(o1.virtualAddrRelative);
			int rva2 = PEFile.uCompare(o2.virtualAddrRelative);
			if (rva1 < rva2)
				return -1;
			if (rva1 > rva2)
				return 1;
			return 0;
		}

	};

	private static String getFillerSectionTag(int flrNum) {
		String flrNumTag = Integer.toHexString(flrNum).toUpperCase();
		while (flrNumTag.length() < 4)
			flrNumTag = "0" + flrNumTag;
		if (flrNumTag.length() > 4)
			flrNumTag = flrNumTag.substring(0, 4);
		return ".flr" + flrNumTag;
	}

	private static boolean isFillerSection(PEFile.Section s) {
		String sTag = s.decodeTag();
		if (sTag.length() == 8 && sTag.startsWith(".flr")) {
			try {
				Integer.parseUnsignedInt(sTag.substring(4), 16);
			} catch (NumberFormatException e) {
				return false;
			}
			return (s.characteristics & PEFile.SECCHR_UNINITIALIZED_DATA) != 0;
		}
		return false;
	}

	private static void removeFillerSections() {
		LinkedList<PEFile.Section> secsToRem = new LinkedList<PEFile.Section>();
		for (PEFile.Section s : peData.sections) {
			if (isFillerSection(s))
				secsToRem.add(s);
		}
		peData.sections.removeAll(secsToRem);
	}

	private static void mallocFiller(int num, int addr, int size) {
		PEFile.Section filler = new PEFile.Section();
		filler.encodeTag(getFillerSectionTag(num));
		filler.virtualAddrRelative = addr;
		filler.virtualSize = size;
		filler.characteristics = PEFile.SECCHR_UNINITIALIZED_DATA | PEFile.SECCHR_READ | PEFile.SECCHR_WRITE;
		peData.malloc(filler);
	}

	// NOTE: I recommend invoking removeFillerSections before this
	private static void fixVirtualLayoutGaps() {
		final int sectionAlignment = peData.getOptionalHeaderInt(0x20);
		// sort the sections so we go by RVA
		LinkedList<PEFile.Section> sectionsSorted = new LinkedList<PEFile.Section>(peData.sections);
		sectionsSorted.sort(sortByRVA);
		// first romp to get first free filler section number
		int flrNum = 0;
		for (PEFile.Section s : sectionsSorted) {
			if (!isFillerSection(s))
				continue;
			String segNumStr = s.decodeTag().substring(4);
			int segNum = 0;
			try {
				segNum = Integer.parseUnsignedInt(segNumStr, 16);
			} catch (NumberFormatException e) {
				// last 4 characters are not a valid hex number
				// not a filler segment, outta here!
				continue;
			}
			if (flrNum < segNum)
				flrNum = segNum;
		}
		flrNum++;
		int lastAddress = 0;
		String lastSeg = null;
		// second romp, this time it's personal
		// if a section's RVA does not equal lastAddress, that means there's a gap in
		// the virtual layout
		// for some reason Windows 10 and apparently *only* Windows 10 hates virtual
		// layout gaps
		// so we plug the gaps up using uninitialized filler sections (.flrXXXX)
		for (PEFile.Section s : sectionsSorted) {
			String curSeg = s.decodeTag();
			if (lastAddress != 0) {
				if (s.virtualAddrRelative != lastAddress) {
					int confirm = JOptionPane.showConfirmDialog(null, String.format(
							"Detected virtual layout gap between segments \"%s\" and \"%s\"!\nThis executable won't work on Windows 10 unless this is fixed.\nFix it?",
							lastSeg, curSeg), "Confirm operation", JOptionPane.WARNING_MESSAGE,
							JOptionPane.YES_NO_OPTION);
					if (confirm != JOptionPane.YES_OPTION) {
						lastSeg = curSeg;
						lastAddress = PEFile.alignForward(s.virtualAddrRelative + s.virtualSize, sectionAlignment);
						continue;
					}
					// create new filler section
					mallocFiller(flrNum++, lastAddress, s.virtualAddrRelative - lastAddress);
				}
			}
			lastSeg = curSeg;
			lastAddress = PEFile.alignForward(s.virtualAddrRelative + s.virtualSize, sectionAlignment);
		}
	}

	public static void patch(ByteBuffer data, int offset) {
		// int shift = 0;
		if (offset >= 0x400000)
			offset -= 0x400000;
		ByteBuffer d = peData.setupRVAPoint(offset);
		if (d != null) {
			// it's within this section
			data.position(0);
			d.put(data);
		}
	}

	private static String int2Hex(int i) {
		return "0x" + Integer.toHexString(i).toUpperCase();
	}

	public static ByteBuffer read(int imgStrOffset1, int size) {
		ByteBuffer retVal = null;
		if (imgStrOffset1 >= 0x400000)
			imgStrOffset1 -= 0x400000;
		ByteBuffer d = peData.setupRVAPoint(imgStrOffset1);
		if (d != null) {
			// make sure we don't overflow
			int available = d.capacity() - d.position();
			size = Math.min(size, available);
			// it's within this section
			byte[] data = new byte[size];
			d.get(data);
			retVal = ByteBuffer.wrap(data);
		}
		if (retVal == null)
			System.err.println("READ FAIL! OFF=" + int2Hex(imgStrOffset1) + ",SZE=" + int2Hex(size));
		return retVal;
	}

}
