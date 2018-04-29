package com.leo.orgadder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class RsrcHandler {

	public RsrcEntry root;

	public static class RsrcEntry {
		public RsrcEntry parent; // if this is null, this entry is the root directory
		public String name; // each char is short-sized
		public int id; // if name is null, use this instead
		public byte[] data; // if null, this is a directory...
		public LinkedList<RsrcEntry> entries; // ...which means this contains its entries
		// for data entries:
		public int dataCodepage;
		public int dataReserved;
		// for directories:
		public int dirCharacteristics;
		public int dirTimestamp;
		public short dirVerMajor, dirVerMinor;
		
		public boolean isDirectory() {
			return data == null;
		}
		
		public RsrcEntry getSubEntry(String name) {
			if (!isDirectory())
				return null;
			for (RsrcEntry entry : entries) {
				if (entry.name == null)
					continue;
				if (entry.name.equals(name))
					return entry;
			}
			return null;
		}
		
		public boolean hasSubEntry(String name) {
			return getSubEntry(name) != null;
		}
		
		public RsrcEntry getSubEntry(int id) {
			if (!isDirectory())
				return null;
			for (RsrcEntry entry : entries) {
				if (entry.name != null)
					continue;
				if (entry.id == id)
					return entry;
			}
			return null;
		}
		
		public boolean hasSubEntry(int id) {
			return getSubEntry(id) != null;
		}
	}

	// Before this constructor, do:
	/*
	peData.sections.remove(rsrcSec);
	rsrcSec.shiftResourceContents(-rsrcSec.virtualAddrRelative);
	 */
	public RsrcHandler(PEFile.Section rsrcSec) {
		root = new RsrcEntry();
		root.entries = new LinkedList<>();
		ByteBuffer data = ByteBuffer.wrap(rsrcSec.rawData);
		data.order(ByteOrder.LITTLE_ENDIAN);
		readDirectory(data, root);
	}

	// After this method, do:
	/*
	peData.malloc(rsrcSec);
	int rsrcSecRVA = rsrcSec.virtualAddrRelative;
	rsrcSec.shiftResourceContents(rsrcSecRVA);
	peData.setOptionalHeaderInt(0x70, rsrcSecRVA);
	 */
	public void write(PEFile.Section rsrcSec) {
		// 0th romp to calculate the section size
		LinkedList<String> allocatedStrings = new LinkedList<>();
		int[] sectionSize = calculateSectionSize(root, allocatedStrings);
		byte[] data = new byte[sectionSize[0]];
		ByteBuffer dBuf = ByteBuffer.wrap(data);
		dBuf.order(ByteOrder.LITTLE_ENDIAN);
		ReferenceMemory refMem = new ReferenceMemory();
		writeDirectory(dBuf, root, refMem);
		writeReferences(dBuf, sectionSize, refMem);
		rsrcSec.rawData = data;
		rsrcSec.virtualSize = data.length;
	}

	private void readDirectory(ByteBuffer data, RsrcEntry root) {
		int posStorage = 0;
		root.dirCharacteristics = data.getInt();
		root.dirTimestamp = data.getInt();
		root.dirVerMajor = data.getShort();
		root.dirVerMinor = data.getShort();
		int entries = data.getShort();
		entries += data.getShort();
		Main.LOGGER.trace("reading " + entries + " entries");
		for (int i = 0; i < entries; i++) {
			RsrcEntry entry = new RsrcEntry();
			entry.parent = root;
			int nameOffset = data.getInt();
			if ((nameOffset & 0x80000000) == 0) {
				// id
				entry.id = nameOffset;
			} else {
				// name
				posStorage = data.position();
				nameOffset &= 0x7FFFFFFF;
				Main.LOGGER.trace("nameOffset=0x" + Integer.toHexString(nameOffset).toUpperCase());
				Main.LOGGER.trace("data.limit()=0x" + Integer.toHexString(data.limit()).toUpperCase());
				data.position(nameOffset);
				int nameLen = data.getShort();
				char[] nameBuf = new char[nameLen];
				for (int j = 0; j < nameLen; j++) {
					nameBuf[j] = (char) data.getShort();
				}
				entry.name = new String(nameBuf, 0, nameLen);
				Main.LOGGER.trace("entry.name=" + entry.name);
				data.position(posStorage);
			}
			handleEntryData(data, entry);
			root.entries.add(entry);
		}
	}

	private void handleEntryData(ByteBuffer data, RsrcEntry entry) {
		int dataOffset = data.getInt();
		int posStorage = data.position();
		if ((dataOffset & 0x80000000) == 0) {
			// data
			Main.LOGGER.trace("DATA,dataOffset=0x" + Integer.toHexString(dataOffset).toUpperCase());
			data.position(dataOffset);
			int dataPos = data.getInt();
			int dataSize = data.getInt();
			entry.dataCodepage = data.getInt();
			entry.dataReserved = data.getInt();
			// start reading data
			data.position(dataPos);
			byte[] entryData = new byte[dataSize];
			data.get(entryData);
			entry.data = entryData;
		} else {
			// subdirectory
			dataOffset &= 0x7FFFFFFF;
			Main.LOGGER.trace("SUBDIR,dataOffset=0x" + Integer.toHexString(dataOffset).toUpperCase());
			entry.entries = new LinkedList<>();
			data.position(dataOffset);
			readDirectory(data, entry);
		}
		data.position(posStorage);
	}

	// return indexes:
	// 0 - total size
	// 1 - directory size
	// 2 - data entry size
	// 3 - string size
	// 4 - total data size
	private int[] calculateSectionSize(RsrcEntry root, LinkedList<String> allocStr) {
		int dirSize = 0x10;
		int dataEntrySize = 0;
		int stringSize = 0;
		int dataSize = 0;
		for (RsrcEntry entry : root.entries) {
			dirSize += 8;
			if (entry.name != null && !allocStr.contains(entry.name)) {
				allocStr.add(entry.name);
				stringSize += 2 + entry.name.length() * 2;
			}
			if (entry.data == null) {
				int[] toAdd = calculateSectionSize(entry, allocStr);
				dirSize += toAdd[1];
				dataEntrySize += toAdd[2];
				stringSize += toAdd[3];
				dataSize += toAdd[4];
			} else {
				dataEntrySize += 0x10;
				dataSize += entry.data.length;
			}
		}
		return new int[] { dirSize + dataEntrySize + stringSize + dataSize, dirSize, dataEntrySize, stringSize,
				dataSize };
	}

	static class ReferenceMemory {
		public HashMap<RsrcEntry, Integer> dirOffsets;
		public HashMap<RsrcEntry, LinkedList<Integer>> dirReferences;
		public HashMap<RsrcEntry, LinkedList<Integer>> dataEntryReferences;
		public HashMap<String, LinkedList<Integer>> stringReferences;

		public ReferenceMemory() {
			dirOffsets = new HashMap<>();
			dirReferences = new HashMap<>();
			dataEntryReferences = new HashMap<>();
			stringReferences = new HashMap<>();
		}

		public void addDirReference(RsrcEntry refEntry, int refPos) {
			LinkedList<Integer> refList = null;
			if (dirReferences.containsKey(refEntry))
				refList = dirReferences.get(refEntry);
			else {
				refList = new LinkedList<>();
				dirReferences.put(refEntry, refList);
			}
			refList.add(refPos);
		}

		public void addDataEntryReference(RsrcEntry refData, int refPos) {
			LinkedList<Integer> refList = null;
			if (dataEntryReferences.containsKey(refData))
				refList = dataEntryReferences.get(refData);
			else {
				refList = new LinkedList<>();
				dataEntryReferences.put(refData, refList);
			}
			refList.add(refPos);
		}

		public void addStringReference(String refString, int refPos) {
			LinkedList<Integer> refList = null;
			if (stringReferences.containsKey(refString))
				refList = stringReferences.get(refString);
			else {
				refList = new LinkedList<>();
				stringReferences.put(refString, refList);
			}
			refList.add(refPos);
		}
	}

	private void writeDirectory(ByteBuffer data, RsrcEntry root, ReferenceMemory refMem) {
		refMem.dirOffsets.put(root, data.position());
		// write unimportant fields
		data.putInt(root.dirCharacteristics);
		data.putInt(root.dirTimestamp);
		data.putShort(root.dirVerMajor);
		data.putShort(root.dirVerMinor);
		// first romp to count name/ID entries
		LinkedList<RsrcEntry> nameEntries = new LinkedList<>(), idEntries = new LinkedList<>();
		short idEntryCount = 0, nameEntryCount = 0;
		for (int i = 0; i < root.entries.size(); i++) {
			RsrcEntry entry = root.entries.get(i);
			if (entry.name == null) {
				idEntries.add(entry);
				idEntryCount++;
			} else {
				nameEntries.add(entry);
				nameEntryCount++;
			}
		}
		LinkedList<RsrcEntry> entries = new LinkedList<>();
		entries.addAll(nameEntries);
		entries.addAll(idEntries);
		// write em out
		data.putShort(nameEntryCount);
		data.putShort(idEntryCount);
		// second romp to actually write it
		// make a subdir list to write *after* writing the entire directory
		LinkedList<RsrcEntry> subdirs = new LinkedList<>();
		for (int i = 0; i < entries.size(); i++) {
			RsrcEntry entry = entries.get(i);
			if (entry.name == null)
				data.putInt(entry.id);
			else {
				refMem.addStringReference(entry.name, data.position());
				data.putInt(0x80000000);
			}
			if (entry.isDirectory()) {
				refMem.addDirReference(entry, data.position());
				data.putInt(0x80000000);
				subdirs.add(entry);
			} else {
				refMem.addDataEntryReference(entry, data.position());
				data.putInt(0);
			}
		}
		// now write the subdirectories
		for (RsrcEntry entry : subdirs)
			writeDirectory(data, entry, refMem);
	}

	private void writeReferences(ByteBuffer data, int[] sectionSize, ReferenceMemory refMem) {
		// write subdirectory references
		for (Map.Entry<RsrcEntry, LinkedList<Integer>> entry : refMem.dirReferences.entrySet()) {
			int off = refMem.dirOffsets.get(entry.getKey()) | 0x80000000;
			for (int refLoc : entry.getValue()) {
				data.position(refLoc);
				data.putInt(off);
			}
		}
		// write actual data, remember offsets
		HashMap<RsrcEntry, Integer> dataOffsets = new HashMap<>();
		data.position(sectionSize[1] + sectionSize[2] + sectionSize[3]);
		for (Map.Entry<RsrcEntry, LinkedList<Integer>> entry : refMem.dataEntryReferences.entrySet()) {
			dataOffsets.put(entry.getKey(), data.position());
			byte[] entryData = entry.getKey().data;
			data.put(entryData);
		}
		// write data entries and their references
		data.position(sectionSize[1]);
		for (Map.Entry<RsrcEntry, LinkedList<Integer>> entry : refMem.dataEntryReferences.entrySet()) {
			int off = data.position();
			int oldPos = data.position();
			for (int refLoc : entry.getValue()) {
				data.position(refLoc);
				data.putInt(off);
			}
			data.position(oldPos);
			RsrcEntry rsrc = entry.getKey();
			data.putInt(dataOffsets.get(rsrc));
			data.putInt(rsrc.data.length);
			data.putInt(rsrc.dataCodepage);
			data.putInt(rsrc.dataReserved);
		}
		// write strings (directory names) and their references
		data.position(sectionSize[1] + sectionSize[2]);
		for (Map.Entry<String, LinkedList<Integer>> entry : refMem.stringReferences.entrySet()) {
			int off = data.position() | 0x80000000;
			int oldPos = data.position();
			for (int refLoc : entry.getValue()) {
				data.position(refLoc);
				data.putInt(off);
			}
			data.position(oldPos);
			String str = entry.getKey();
			short strLen = (short) str.length();
			data.putShort(strLen);
			for (int i = 0; i < strLen; i++)
				data.putShort((short) str.charAt(i));
		}

	}

}
