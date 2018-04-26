package com.leo.orgadder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
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
				readONTSection();
				for (int i = 0; i < orgNames.size(); i++)
					System.out.println(i + " - " + orgNames.get(i));
				JOptionPane.showMessageDialog(null, "Done .ont");
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

	private static Vector<String> orgNames;

	private static final int ONT_RVA = 0x4981E8;
	private static final int[] ONT_REFS = { 0x420F17, 0x420F60 };

	private static void initOrgNames(Vector<byte[]> orgNamesRaw) {
		final int orgNameCount = orgNamesRaw.size();
		orgNames = new Vector<>(orgNameCount);
		for (int i = 0; i < orgNameCount; i++) {
			byte[] orgName = orgNamesRaw.get(i);
			int maxL = 0;
			for (int j = 0; j < orgName.length; j++)
				if (orgName[j] != 0)
					maxL = j + 1;
			orgNames.add(i, new String(orgName, 0, maxL, Charset.forName("Windows-1252")));
		}
	}

	private static int writeONTSection() {
		final int orgCount = orgNames.size();
		int ontSize = orgCount * 4;
		int orgNameSize = 0;
		Vector<byte[]> orgNamesRaw = new Vector<>(orgCount);
		for (int i = 0; i < orgCount; i++) {
			String src = orgNames.get(i);
			int dstLen = src.length();
			if (dstLen % 4 == 0)
				dstLen += 4;
			else
				dstLen = (dstLen / 4 + 1) * 4;
			byte[] dst = new byte[dstLen];
			byte[] srcDat = src.getBytes(Charset.forName("Windows-1252"));
			System.arraycopy(srcDat, 0, dst, 0, srcDat.length);
			orgNameSize += dstLen;
			orgNamesRaw.add(i, dst);
		}
		byte[] data = new byte[ontSize + 4 + orgNameSize];
		// data size: pointers to names + 4 bytes to mark end of pointers + the names
		// themselves
		int usedBytes = 0;
		for (int i = 0; i < orgNamesRaw.size(); i++) {
			byte[] name = orgNamesRaw.get(i);
			System.arraycopy(name, 0, data, ontSize + 4 + usedBytes, name.length);
			usedBytes += name.length;
		}
		// remove all filler sections (as you do, before adding a new section)
		removeFillerSections();
		PEFile.Section ontSec = null;
		int ontSecId = peData.getSectionIndexByTag(".ont");
		if (ontSecId == -1)
			// ONT section does not yet exist, create new
			ontSec = new PEFile.Section();
		else
			// ONT section already exists, remove it for later reinstall
			ontSec = peData.sections.remove(ontSecId);
		ontSec.encodeTag(".ont");
		ontSec.rawData = data;
		ontSec.virtualSize = data.length;
		ontSec.metaLinearize = false;
		ontSec.characteristics = PEFile.SECCHR_INITIALIZED_DATA | PEFile.SECCHR_READ;
		peData.malloc(ontSec);
		// reinstall those filler sections
		fixVirtualLayoutGaps();
		// now that we have the .onl segment's RVA, we can fill the start of data with
		// the pointers to the names
		ByteBuffer dataBuf = ByteBuffer.wrap(data);
		dataBuf.order(ByteOrder.LITTLE_ENDIAN);
		final int ontNewRVA = ontSec.virtualAddrRelative + 0x400000;
		int nameRVA = ontNewRVA + ontSize + 4;
		for (int i = 0; i < orgNamesRaw.size(); i++) {
			dataBuf.putInt(nameRVA);
			nameRVA += orgNamesRaw.get(i).length;
		}
		// return the ORG name table's new RVA, for setupONTSection
		return ontNewRVA;
	}

	private static void setupONTSection() {
		ByteBuffer listBuf = peData.setupRVAPoint(ONT_RVA - 0x400000);
		int ontSize = 0;
		Vector<byte[]> orgNamesRaw = new Vector<>();
		int orgNameSize = 0;
		while (true) {
			int orgAddr = listBuf.getInt();
			if (orgAddr < 0x400000)
				// if next address is before image base, it's probably not an address
				// i.e we're overflowing and need to stop
				break;
			ontSize += 4;
			byte[] orgNameTmp = new byte[0x80];
			int index = 0;
			while (true) {
				ByteBuffer cBuf = read(orgAddr++, 1);
				byte c = cBuf.get(0);
				if (c == 0) {
					// NAME LENGTH RULES:
					// 1. always multiples of 4
					// 2. if length is divisible by 4, add 4
					if (index % 4 == 0)
						index += 4;
					else
						index = (index / 4 + 1) * 4;
					break;
				}
				orgNameTmp[index++] = toUpperChar(c);
			}
			byte[] orgName = new byte[index];
			System.arraycopy(orgNameTmp, 0, orgName, 0, index);
			orgNameSize += orgName.length;
			orgNamesRaw.add(orgName);
		}
		System.out.println("ORG name pointer table size is " + int2Hex(ontSize));
		System.out.println("Read " + orgNamesRaw.size() + " names for a total of " + int2Hex(orgNameSize) + " bytes");
		// now that we're read everything, initialize orgNames...
		initOrgNames(orgNamesRaw);
		// ...and then write the ONT section...
		int ontNewRVA = writeONTSection();
		// ...and finally, patch references
		ByteBuffer buf = ByteBuffer.allocate(4);
		buf.order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(0, ontNewRVA);
		for (int ref : ONT_REFS)
			patch(buf, ref);
	}

	private static void readONTSection() {
		int ontSecID = peData.getSectionIndexByTag(".ont");
		if (ontSecID == -1) {
			setupONTSection();
			return;
		}
		PEFile.Section ontSec = peData.sections.get(ontSecID);
		ByteBuffer listBuf = ByteBuffer.wrap(ontSec.rawData);
		listBuf.order(ByteOrder.LITTLE_ENDIAN);
		Vector<byte[]> orgNamesRaw = new Vector<>();
		while (true) {
			int orgAddr = listBuf.getInt();
			if (orgAddr < 0x400000)
				// if next address is before image base, it's probably not an address
				// i.e we're overflowing and need to stop
				break;
			byte[] orgNameTmp = new byte[0x80];
			int index = 0;
			while (true) {
				// since we don't have to preserve null characters here, we can just stop
				// reading when we encounter one
				ByteBuffer cBuf = read(orgAddr++, 1);
				byte c = cBuf.get(0);
				if (c == 0)
					break;
				orgNameTmp[index++] = toUpperChar(c);
			}
			byte[] orgName = new byte[index];
			System.arraycopy(orgNameTmp, 0, orgName, 0, index);
			orgNamesRaw.add(orgName);
		}
		initOrgNames(orgNamesRaw);
	}

	private static char toUpperChar(char c) {
		if (c >= 'a' && c <= 'z')
			c += 'A' - 'a';
		return c;
	}

	private static byte toUpperChar(byte c) {
		return (byte) toUpperChar((char) c);
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
