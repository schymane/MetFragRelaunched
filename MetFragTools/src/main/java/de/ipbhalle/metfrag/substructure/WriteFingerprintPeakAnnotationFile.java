package de.ipbhalle.metfrag.substructure;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Hashtable;
import java.util.ArrayList;

import de.ipbhalle.metfraglib.FastBitArray;
import de.ipbhalle.metfraglib.database.LocalCSVDatabase;
import de.ipbhalle.metfraglib.database.LocalPSVDatabase;
import de.ipbhalle.metfraglib.exceptions.MultipleHeadersFoundInInputDatabaseException;
import de.ipbhalle.metfraglib.interfaces.ICandidate;
import de.ipbhalle.metfraglib.interfaces.IDatabase;
import de.ipbhalle.metfraglib.list.CandidateList;
import de.ipbhalle.metfraglib.parameter.VariableNames;
import de.ipbhalle.metfraglib.settings.Settings;
import de.ipbhalle.metfraglib.substructure.FingerprintGroup;
import de.ipbhalle.metfraglib.substructure.MassToFingerprintGroupList;
import de.ipbhalle.metfraglib.substructure.MassToFingerprintGroupListCollection;

public class WriteFingerprintPeakAnnotationFile {

	/*
	 * write annotation file
	 * 
	 * filename - input file name
	 * probtype - probability type: 0 - counts; 1 - P ( s | p ); 2 - P ( p | s ); 3 - P ( p , s ) from s; 4 - P ( p , s ) from p; 5 - P ( s | p ) P ( p | s ) P ( p , s )_s P ( p , s )_p
	 * occurThresh
	 * output
	 * csv
	 * fingerprinttype
	 * includeNonExplained
	 * 
	 */
	
	public static void main(String[] args) throws MultipleHeadersFoundInInputDatabaseException, Exception {
		java.util.Hashtable<String, String> readParameters = readParameters(args);
		if(!readParameters.containsKey("filename")) {
			System.err.println("filename missing");
			System.exit(1);
		}
		if(!readParameters.containsKey("probtype")) {
			System.err.println("probtype missing");
			System.exit(1);
		}
		
		String filename = readParameters.get("filename");

		Integer probabilityType = Integer.parseInt(readParameters.get("probtype"));
		String output = null;
		Integer occurThresh = null;
		String csv = "";
		String fingerprinttype = "";
		String includeNonExplainedString = "";
		if(readParameters.containsKey("output")) output = readParameters.get("output");
		if(readParameters.containsKey("occurThresh")) occurThresh = Integer.parseInt(readParameters.get("occurThresh"));
		if(readParameters.containsKey("csv")) csv = (String)readParameters.get("csv");
		if(readParameters.containsKey("fingerprinttype")) fingerprinttype = (String)readParameters.get("fingerprinttype");
		if(readParameters.containsKey("includeNonExplained")) includeNonExplainedString = (String)readParameters.get("includeNonExplained");
		
		ArrayList<Double> peakMassesSorted = new ArrayList<Double>();
		ArrayList<String> fingerprintsSorted = new ArrayList<String>();
		
		StringBuilder nonExplainedPeaksString = new StringBuilder();
		ArrayList<Double> nonExplainedPeaks = new ArrayList<Double>();
		ArrayList<Integer> peakMassCounts = new ArrayList<Integer>();
		
		boolean includeNonExplained = true;
		if(includeNonExplainedString.equals("F") || includeNonExplainedString.equals("f")
				|| includeNonExplainedString.equals("False") || includeNonExplainedString.equals("false")
				|| includeNonExplainedString.equals("FALSE"))
			includeNonExplained = false;
		
		Settings settings = new Settings();
		settings.set(VariableNames.LOCAL_DATABASE_PATH_NAME, filename);
		IDatabase db = null;
		if(csv == "1") db = new LocalCSVDatabase(settings);
		else if (csv.equals("auto")) {
			if(filename.endsWith("psv")) db = new LocalPSVDatabase(settings);
			else db = new LocalCSVDatabase(settings);
		}
		else db = new LocalPSVDatabase(settings);
		java.util.ArrayList<String> ids = db.getCandidateIdentifiers();
		CandidateList candidateList = db.getCandidateByIdentifier(ids);
		//SmilesOfExplPeaks
		for(int i = 0; i < candidateList.getNumberElements(); i++) {
			ICandidate candidate = candidateList.getElement(i);
			String fingerprintsOfExplPeaks = (String)candidate.getProperty("FragmentFingerprintOfExplPeaks" + fingerprinttype);
			if(fingerprintsOfExplPeaks.equals("NA") || fingerprintsOfExplPeaks.length() == 0) continue;
			fingerprintsOfExplPeaks = fingerprintsOfExplPeaks.trim();
			
			String[] fingerprintPairs = fingerprintsOfExplPeaks.split(";");
			
			for(int k = 0; k < fingerprintPairs.length; k++) {
				String[] tmp1 = fingerprintPairs[k].split(":");
				Double peak1 = Double.parseDouble(tmp1[0]);
				String fingerprint = null;
				try {
					fingerprint = tmp1[1];
					addSortedFeature(peak1, fingerprint, peakMassesSorted, fingerprintsSorted);
				}
				catch(Exception e) {
					continue;
				}
			}

			String nonExplMasses = (String)candidate.getProperty("NonExplainedPeaks");
			if(!nonExplMasses.equals("NA")) {
				String[] tmp = nonExplMasses.split(";");
				for(int k = 0; k < tmp.length; k++) {
					double mass = Double.parseDouble(tmp[k]);
					if(mass > 2) {
						addMassSorted(mass, peakMassCounts, nonExplainedPeaks);
					}
				}
			}
		}

		if(nonExplainedPeaks.size() == 0 || !includeNonExplained) nonExplainedPeaksString.append("NA");
		else {
			nonExplainedPeaksString.append(nonExplainedPeaks.get(0));
			if(peakMassCounts.get(0) > 1) {
				nonExplainedPeaksString.append(":");
				nonExplainedPeaksString.append(peakMassCounts.get(0));
			}
			for(int i = 1; i < nonExplainedPeaks.size(); i++) {
				nonExplainedPeaksString.append(";");
				nonExplainedPeaksString.append(nonExplainedPeaks.get(i));
				if(peakMassCounts.get(i) > 1) {
					nonExplainedPeaksString.append(":");
					nonExplainedPeaksString.append(peakMassCounts.get(i));
				}
			}
		}
		
		
		MassToFingerprintGroupListCollection peakToFingerprintGroupListCollection = new MassToFingerprintGroupListCollection();
		//print(peakMassesSorted, fingerprintsSorted);
		System.out.println(peakMassesSorted.size() + " peak fingerprint pairs");

		Integer id = 0;
		Hashtable<Integer, ArrayList<Double>> grouplistid_to_masses = new Hashtable<Integer, ArrayList<Double>>();
		for(int i = 0; i < peakMassesSorted.size(); i++) {
			Double currentPeak = peakMassesSorted.get(i);
			//MassToFingerprintGroupList peakToFingerprintGroupList = peakToFingerprintGroupListCollection.getElementByPeakInterval(currentPeak, mzppm, mzabs);
			MassToFingerprintGroupList peakToFingerprintGroupList = peakToFingerprintGroupListCollection.getElementByPeak(currentPeak);
			if(peakToFingerprintGroupList == null) {
				peakToFingerprintGroupList = new MassToFingerprintGroupList(currentPeak);
				peakToFingerprintGroupList.setId(id);
				FingerprintGroup obj = new FingerprintGroup(0.0, null, null, null);
				obj.setFingerprint(fingerprintsSorted.get(i));
				obj.incrementNumberObserved();
				peakToFingerprintGroupList.addElement(obj);
				peakToFingerprintGroupListCollection.addElementSorted(peakToFingerprintGroupList);
				addMass(grouplistid_to_masses, id, currentPeak);
				id++;
			}
			else {
				Integer current_id = peakToFingerprintGroupList.getId();
				addMass(grouplistid_to_masses, current_id, currentPeak);
				FingerprintGroup fingerprintGroup = peakToFingerprintGroupList.getElementByFingerprint(new FastBitArray(fingerprintsSorted.get(i)));
				if(fingerprintGroup != null) {
					fingerprintGroup.incrementNumberObserved();
				}
				else {
					fingerprintGroup = new FingerprintGroup(0.0, null, null, null);
					fingerprintGroup.setFingerprint(fingerprintsSorted.get(i));
					fingerprintGroup.incrementNumberObserved();
					peakToFingerprintGroupList.addElement(fingerprintGroup);
				}
			}
		}
		System.out.println("before filtering " + peakToFingerprintGroupListCollection.getNumberElements());
		
		//peakToFingerprintGroupListCollection.updatePeakMass(mzppm, mzabs);
		peakToFingerprintGroupListCollection.updatePeakMass(grouplistid_to_masses);
	
		// test filtering
		if(occurThresh != null) peakToFingerprintGroupListCollection.filterByOccurence(occurThresh);
		
		peakToFingerprintGroupListCollection.annotateIds();
		//get absolute numbers of single substructure occurences
		//N^(s)
		int[] substrOccurences = peakToFingerprintGroupListCollection.calculateSubstructureAbsoluteProbabilities();
		int[] peakOccurences = peakToFingerprintGroupListCollection.calculatePeakAbsoluteProbabilities();

		//counts
		if(probabilityType == 0) {
			// calculate P ( s | p ) 
			peakToFingerprintGroupListCollection.updateConditionalProbabilities();
			peakToFingerprintGroupListCollection.setProbabilityToNumberObserved();
			peakToFingerprintGroupListCollection.sortElementsByProbability();
		}
		//P ( s | p ) 
		if(probabilityType == 1) {
			// calculate P ( s | p ) 
			peakToFingerprintGroupListCollection.updateConditionalProbabilities();
			peakToFingerprintGroupListCollection.setProbabilityToConditionalProbability_sp();
			peakToFingerprintGroupListCollection.sortElementsByProbability();
		}
		//P ( p | s ) 
		if(probabilityType == 2) {
			System.out.println("annotating IDs");
			// calculate P ( p | s ) 
			peakToFingerprintGroupListCollection.updateProbabilities(substrOccurences);
			
			peakToFingerprintGroupListCollection.setProbabilityToConditionalProbability_ps();
			peakToFingerprintGroupListCollection.sortElementsByProbability();
		}
		
		//P ( p , s )_s 
		if(probabilityType == 3) {
			System.out.println("annotating IDs");
			// calculate P ( p , s ) 
			peakToFingerprintGroupListCollection.updateJointProbabilitiesWithSubstructures(substrOccurences);
			
			peakToFingerprintGroupListCollection.setProbabilityToJointProbability();
			peakToFingerprintGroupListCollection.sortElementsByProbability();
		}

		//P ( p , s )_p
		if(probabilityType == 4) {
			System.out.println("annotating IDs");
			// calculate P ( p , s ) 
			peakToFingerprintGroupListCollection.updateJointProbabilitiesWithPeaks(peakOccurences);
			
			peakToFingerprintGroupListCollection.setProbabilityToJointProbability();
			peakToFingerprintGroupListCollection.sortElementsByProbability();
		}
		
		//P ( s | p ) P ( p | s ) P( s, p )_s
		//SUMMARY "number of different pairs (f,m) matched" "sum of all occurrences of all (f,m)" "number of different pairs (_,m)" "number of all different pairs (f,m)"
		if(probabilityType == 5) {
			System.out.println("annotating IDs");
			peakToFingerprintGroupListCollection.updateConditionalProbabilities();
			peakToFingerprintGroupListCollection.updateProbabilities(substrOccurences);
			peakToFingerprintGroupListCollection.updateJointProbabilitiesWithSubstructures(substrOccurences);
			
			peakToFingerprintGroupListCollection.setProbabilityToConditionalProbability_sp();
			peakToFingerprintGroupListCollection.sortElementsByProbability();
			if(output == null) peakToFingerprintGroupListCollection.print();
			else {
				BufferedWriter bwriter = new BufferedWriter(new FileWriter(new File(output + "_1")));
				bwriter.write(nonExplainedPeaksString.toString());
				bwriter.newLine();
				bwriter.write(peakToFingerprintGroupListCollection.toString());
				bwriter.write("SUMMARY " + getNumberMatchedElements(peakToFingerprintGroupListCollection) + " " + getNumberOccurences(peakToFingerprintGroupListCollection) + " " + getNumberNonMatchedElements(peakToFingerprintGroupListCollection) + " " + getNumberElements(peakToFingerprintGroupListCollection));
				bwriter.newLine();
				bwriter.close();
			}
			
			peakToFingerprintGroupListCollection.setProbabilityToConditionalProbability_ps();
			peakToFingerprintGroupListCollection.sortElementsByProbability();
			if(output == null) peakToFingerprintGroupListCollection.print();
			else {
				BufferedWriter bwriter = new BufferedWriter(new FileWriter(new File(output + "_2")));
				bwriter.write(nonExplainedPeaksString.toString());
				bwriter.newLine();
				bwriter.write(peakToFingerprintGroupListCollection.toString());
				bwriter.write("SUMMARY " + getNumberMatchedElements(peakToFingerprintGroupListCollection) + " " + getNumberOccurences(peakToFingerprintGroupListCollection) + " " + getNumberNonMatchedElements(peakToFingerprintGroupListCollection) + " " + getNumberElements(peakToFingerprintGroupListCollection));
				bwriter.newLine();
				bwriter.close();
			}

			peakToFingerprintGroupListCollection.setProbabilityToJointProbability();
			peakToFingerprintGroupListCollection.sortElementsByProbability();
			if(output == null) peakToFingerprintGroupListCollection.print();
			else {
				BufferedWriter bwriter = new BufferedWriter(new FileWriter(new File(output + "_3")));
				bwriter.write(nonExplainedPeaksString.toString());
				bwriter.newLine();
				bwriter.write(peakToFingerprintGroupListCollection.toString());
				bwriter.write("SUMMARY " + getNumberMatchedElements(peakToFingerprintGroupListCollection) + " " + getNumberOccurences(peakToFingerprintGroupListCollection) + " " + getNumberNonMatchedElements(peakToFingerprintGroupListCollection) + " " + getNumberElements(peakToFingerprintGroupListCollection));
				bwriter.newLine();
				bwriter.close();
			}
		}

		if(probabilityType != 5) {
			if(output == null) peakToFingerprintGroupListCollection.print();
			else {
				System.out.println("writing to output");
				BufferedWriter bwriter = new BufferedWriter(new FileWriter(new File(output)));
				bwriter.write(nonExplainedPeaksString.toString());
				bwriter.newLine();
				bwriter.write(peakToFingerprintGroupListCollection.toString());
				bwriter.write("SUMMARY " + getNumberMatchedElements(peakToFingerprintGroupListCollection) + " " + getNumberOccurences(peakToFingerprintGroupListCollection) + " " + getNumberNonMatchedElements(peakToFingerprintGroupListCollection) + " " + getNumberElements(peakToFingerprintGroupListCollection));
				bwriter.newLine();
				bwriter.close();
			}
		}
	}
	
	public static int getNumberElements(MassToFingerprintGroupListCollection peakToFingerprintGroupListCollections) {
		int count = 0;
		for(int i = 0; i < peakToFingerprintGroupListCollections.getNumberElements(); i++) {
			MassToFingerprintGroupList groupList = peakToFingerprintGroupListCollections.getElement(i);
			count += groupList.getNumberElements();
		}
		return count;
	}
	
	public static int getNumberMatchedElements(MassToFingerprintGroupListCollection peakToFingerprintGroupListCollections) {
		int count = 0;
		for(int i = 0; i < peakToFingerprintGroupListCollections.getNumberElements(); i++) {
			MassToFingerprintGroupList groupList = peakToFingerprintGroupListCollections.getElement(i);
			for(int k = 0; k < groupList.getNumberElements(); k++) 
				if(groupList.getElement(k).getFingerprint().getSize() != 1) count ++;
		}
		return count;
	}
	
	public static void addMassSorted(Double mass, ArrayList<Integer> counts, ArrayList<Double> masses) {
		int index = 0;
		while(index < masses.size()) {
			int comp = Double.compare(mass, masses.get(index));
			if(comp > 0) index++;
			else if(comp == 0) {
				counts.set(index, counts.get(index) + 1);
				return;
			} else break;
		}
		masses.add(index, mass);
		counts.add(index, 1);
	}
	
	public static int getNumberNonMatchedElements(MassToFingerprintGroupListCollection peakToFingerprintGroupListCollections) {
		int count = 0;
		for(int i = 0; i < peakToFingerprintGroupListCollections.getNumberElements(); i++) {
			MassToFingerprintGroupList groupList = peakToFingerprintGroupListCollections.getElement(i);
			for(int k = 0; k < groupList.getNumberElements(); k++) 
				if(groupList.getElement(k).getFingerprint().getSize() == 1) count ++;
		}
		return count;
	}

	public static void addMass(Hashtable<Integer, ArrayList<Double>> grouplistid_to_masses, Integer id, double mass) {
		if(grouplistid_to_masses.containsKey(id) && grouplistid_to_masses.get(id) != null) {
			grouplistid_to_masses.get(id).add(mass);
		} else {
			ArrayList<Double> new_masses = new ArrayList<Double>();
			new_masses.add(mass);
			grouplistid_to_masses.put(id, new_masses);
		}
	}
	
	public static int getNumberOccurences(MassToFingerprintGroupListCollection peakToFingerprintGroupListCollections) {
		int count = 0;
		for(int i = 0; i < peakToFingerprintGroupListCollections.getNumberElements(); i++) {
			MassToFingerprintGroupList groupList = peakToFingerprintGroupListCollections.getElement(i);
			for(int j = 0; j < groupList.getNumberElements(); j++) {
				count += groupList.getElement(j).getNumberObserved();
			}
		}
		return count;
	}

	public static void addSortedFeature(double mass, String fingerprint, ArrayList<Double> masses, ArrayList<String> fingerprints) {
		int index = 0;
		while(index < masses.size() && masses.get(index) < mass) {
			index++;
		}
		masses.add(index, mass);
		fingerprints.add(index, fingerprint);
	}
	
	public static java.util.Hashtable<String, String> readParameters(String[] params) {
		java.util.Hashtable<String, String> parameters = new java.util.Hashtable<String, String>();
		for(int i = 0; i < params.length; i++) {
			String param = params[i];
			String[] tmp = param.split("=");
			if(tmp.length != 2) continue;
			parameters.put(tmp[0], tmp[1]);
		}
		return parameters;
	}
	
	public static void print(ArrayList<Double> peakMassesSorted, ArrayList<String> fingerprintsSorted) {
		for(int i = 0; i < peakMassesSorted.size(); i++) {
			System.out.println(peakMassesSorted.get(i) + " " + fingerprintsSorted.get(i));
		}
	}
	
}
