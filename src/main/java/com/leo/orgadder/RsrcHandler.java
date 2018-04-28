package com.leo.orgadder;

public class RsrcHandler {

	private static PEFile peData;
	private static PEFile.Section rsrcSec;

	public static void setPEData(PEFile peData) {
		RsrcHandler.peData = peData;
		int rsrcSecId = peData.getResourcesIndex();
		if (rsrcSecId == -1)
			throw new RuntimeException("No .rsrc section!");
		rsrcSec = peData.sections.get(rsrcSecId);
		readRsrcSection();
	}

	private static void readRsrcSection() {
		// TODO read resources
	}

	public static void writeRsrcSection() {
		peData.sections.remove(rsrcSec);
		rsrcSec.shiftResourceContents(-rsrcSec.virtualAddrRelative);
		// TODO build new data array
		peData.malloc(rsrcSec);
		int rsrcSecRVA = rsrcSec.virtualAddrRelative;
		rsrcSec.shiftResourceContents(rsrcSecRVA);
		peData.setOptionalHeaderInt(0x70, rsrcSecRVA);
	}
	
	// TODO Add methods to manipulate entries

}
