package de.ipbhalle.metfraglib.writer;

import java.io.File;
import java.io.IOException;

import de.ipbhalle.metfraglib.interfaces.IList;
import de.ipbhalle.metfraglib.interfaces.IWriter;
import de.ipbhalle.metfraglib.settings.Settings;

public class CandidateListWriterCML implements IWriter {

	public boolean write(IList list, String filename, String path, Settings settings) throws IOException {
		return this.write(list, filename, path);
	}
	
	public boolean write(IList list, String filename, String path) {
		// TODO Auto-generated method stub
		return false;
	}

	public void nullify() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean write(IList list, String filename) throws Exception {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean writeFile(File file, IList list) throws Exception {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean writeFile(File file, IList list, Settings settings) throws Exception {
		// TODO Auto-generated method stub
		return false;
	}

}
