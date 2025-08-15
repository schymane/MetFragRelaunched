package de.ipbhalle.metfraglib.process;

import de.ipbhalle.metfraglib.additionals.BondEnergies;
import de.ipbhalle.metfraglib.collection.PostProcessingCandidateFilterCollection;
import de.ipbhalle.metfraglib.collection.PreProcessingCandidateFilterCollection;
import de.ipbhalle.metfraglib.database.LocalPropertyFileDatabase;
import de.ipbhalle.metfraglib.exceptions.ScorePropertyNotDefinedException;
import de.ipbhalle.metfraglib.interfaces.*;
import de.ipbhalle.metfraglib.list.*;
import de.ipbhalle.metfraglib.parameter.ClassNames;
import de.ipbhalle.metfraglib.parameter.VariableNames;
import de.ipbhalle.metfraglib.settings.MetFragGlobalSettings;
import de.ipbhalle.metfraglib.settings.MetFragSingleProcessSettings;
import de.ipbhalle.metfraglib.settings.Settings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CombinedMetFragProcess implements Runnable {

	//settings object containing all parameters
	private MetFragGlobalSettings globalSettings;
	//database object for candidate retrieval
	private IDatabase database;
	//peaklist reader to generate the peaklist -> m/z intensity 
	private IPeakListReader peakListReader; 
	//candidate filters
	private PreProcessingCandidateFilterCollection preProcessingCandidateFilterCollection;
	private PostProcessingCandidateFilterCollection postProcessingCandidateFilterCollection;
	//candidate list -> later also containing the scored candidates
	private CandidateList sortedScoredCandidateList;
	private int numberCandidatesBeforeFilter;
	private boolean threadStoppedExternally = false;
	//threads to process single candidates
	private CombinedSingleCandidateMetFragProcess[] processes;
	//process status object -> stores values about metfrag's processing status
	private ProcessingStatus processingStatus;
	
	private ExecutorService executer;

    protected static final Logger logger = LogManager.getLogger();

    /**
	 * constructore needs settings object
	 * 
	 * @param globalSettings
	 */
	public CombinedMetFragProcess(MetFragGlobalSettings globalSettings) {
		this.processes = null;
		this.globalSettings = globalSettings;
		//set log level
        Configurator.setLevel(logger.getName(), (Level)this.globalSettings.get(VariableNames.LOG_LEVEL_NAME));
		//init processing status object
		//inits database, peaklist reader
		this.initialise();
		//init pre- and post-processing filters
		this.initialiseCandidateFilters();
	}
	
	/*
	 * retrieve the candidates from the database 
	 */
	public boolean retrieveCompounds() throws Exception {
		this.processes = null;
		java.util.ArrayList<String> databaseCandidateIdentifiers = this.database.getCandidateIdentifiers();
		if(this.globalSettings.containsKey(VariableNames.MAXIMUM_CANDIDATE_LIMIT_TO_STOP_NAME) && this.globalSettings.get(VariableNames.MAXIMUM_CANDIDATE_LIMIT_TO_STOP_NAME) != null) {
			int limit = (Integer)this.globalSettings.get(VariableNames.MAXIMUM_CANDIDATE_LIMIT_TO_STOP_NAME);
			if(limit < databaseCandidateIdentifiers.size()) {
				this.logger.info(databaseCandidateIdentifiers.size() + " candidate(s) exceeds the defined limit (MaxCandidateLimitToStop = " + limit + ")");
				return false;
			}
		}
		this.sortedScoredCandidateList = this.database.getCandidateByIdentifier(databaseCandidateIdentifiers);
		this.database.nullify();
		numberCandidatesBeforeFilter = this.sortedScoredCandidateList.getNumberElements();
		this.logger.info("Got " + numberCandidatesBeforeFilter + " candidate(s)");
		//in case external property file is defined, initialise external property values
		//TODO: ExternalPropertyPath needs to be added to VariableNames
		if(this.globalSettings.containsKey("ExternalPropertyPath") && this.globalSettings.get("ExternalPropertyPath") != null) {
			this.initExternalProperties(
					(String)this.globalSettings.get("ExternalPropertyPath"),
					this.sortedScoredCandidateList,
					(String[])this.globalSettings.get(VariableNames.METFRAG_SCORE_TYPES_NAME)
			);
		}
		return true;
	}
	
	/**
	 * update settings given by the new settings object
	 * includes database, peaklist reader, candidate filters
	 * 
	 * @param globalSettings
	 */
	public void renewSettings(MetFragGlobalSettings globalSettings) {
		this.processes = null;
		this.globalSettings = globalSettings;
        Configurator.setLevel(logger.getName(), (Level)this.globalSettings.get(VariableNames.LOG_LEVEL_NAME));
		this.initialise();
		this.initialiseCandidateFilters();
	}
	
	/*
	 * starts global metfrag process that starts a single thread for each candidate
	 * 
	 * @see java.lang.Runnable#run()
	 */
	public void run() {
		this.processes = null;
		this.threadStoppedExternally = false;
			
		/*
		 * read peak list and store in settings object
		 * store database object
		 */
		try {
			this.globalSettings.set(VariableNames.PEAK_LIST_NAME, this.peakListReader.read());
		} catch (Exception e) {
			this.logger.error("Error when reading peak list.");
			e.printStackTrace();
			return;
		}
		this.globalSettings.set(VariableNames.MINIMUM_FRAGMENT_MASS_LIMIT_NAME, ((DefaultPeakList)this.globalSettings.get(VariableNames.PEAK_LIST_NAME)).getMinimumMassValue());

		this.processes = new CombinedSingleCandidateMetFragProcess[this.sortedScoredCandidateList.getNumberElements()];

		//reset processing status
		this.processingStatus.setProcessStatusString("Processing Candidates");
		this.processingStatus.setNumberCandidates(this.sortedScoredCandidateList.getNumberElements());
		this.processingStatus.setNumberFinishedCandidates(0);
		this.processingStatus.setNextPercentageValue(1);
		//initialise all necessary score parameters
		//these parameters are shared over all single candidate thread instances
		this.initialiseScoresGlobal(this.globalSettings);
		/*
		 * prepare single MetFrag threads
		 */
		for(int i = 0; i < this.sortedScoredCandidateList.getNumberElements(); i++) 
		{
			/*
			 * local settings for each thread stores a reference to the global settings
			 */
			MetFragSingleProcessSettings singleProcessSettings = new MetFragSingleProcessSettings(this.globalSettings);
			/*
			 * necessary to define number of hydrogens and make the implicit
			 */
			this.sortedScoredCandidateList.getElement(i).setUseSmiles((Boolean)this.globalSettings.get(VariableNames.USE_SMILES_NAME));
			CombinedSingleCandidateMetFragProcess scmfp = new CombinedSingleCandidateMetFragProcess(singleProcessSettings, this.sortedScoredCandidateList.getElement(i));
			scmfp.setPreProcessingCandidateFilterCollection(this.preProcessingCandidateFilterCollection);
			
			this.processes[i] = scmfp;
		}
		
		/*
		 * define executer thread to run MetFrag process
		 */
		this.executer = Executors.newFixedThreadPool((Byte)this.globalSettings.get(VariableNames.NUMBER_THREADS_NAME));
		/* 
		 * ###############
		 * 	run processes
		 * ###############
		 */
		for(CombinedSingleCandidateMetFragProcess scmfp : this.processes) {
			this.executer.execute(scmfp);
		}
		this.executer.shutdown(); 
	    while(!this.executer.isTerminated())
		{
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
	    if(this.threadStoppedExternally) {
	    	return;
	    }
	    /*
	     * retrieve the result
	     */
	    ScoredCandidateList scoredCandidateList = new ScoredCandidateList();
	    if(this.processes == null) return;
	    
	    /**
	     * perform post processing of scores
	     */
	    this.globalSettings.set(VariableNames.METFRAG_PROCESSES_NAME, this.processes);
	    this.postProcessScoresGlobal(this.globalSettings); 
	    
	    int numberCandidatesProcessed = 0;
		for(CombinedSingleCandidateMetFragProcess scmfp : this.processes) {
			/*
			 * check whether the single run was successful
			 */
			if(scmfp.wasSuccessful()) {
				try {
					scmfp.singlePostCalculateScores();
					scmfp.assignScores();
				} catch (Exception e) {
					e.printStackTrace();
					this.logger.error("Error when processing candidate ID " + scmfp.getScoredPrecursorCandidate().getIdentifier());
					scmfp.getFragmenterAssignerScorer().nullifyScoresCollection();
					return;
				}
				numberCandidatesProcessed++;
				ICandidate[] candidates = scmfp.getScoredPrecursorCandidates();
				for(int i = 0; i < candidates.length; i++) scoredCandidateList.addElement(candidates[i]);
				//important to eliminate static variables
				scmfp.getFragmenterAssignerScorer().nullifyScoresCollection();
			}
		}
		/*
		 * normalise scores of the candidate list 
		 */
		try {
			this.sortedScoredCandidateList = scoredCandidateList.normaliseScores(
				(Double[])this.globalSettings.get(VariableNames.METFRAG_SCORE_WEIGHTS_NAME), 
				(String[])this.globalSettings.get(VariableNames.METFRAG_SCORE_TYPES_NAME),
				(String[])this.globalSettings.get(VariableNames.SCORE_NAMES_NOT_TO_SCALE)
			);
		} catch (ScorePropertyNotDefinedException e) {
			this.logger.error(e.getMessage());
		}
		
		/*
		 * filter candidates by post processing filter
		 */
		if(this.sortedScoredCandidateList.getNumberElements() != this.numberCandidatesBeforeFilter)
			this.logger.info("Processed " + numberCandidatesProcessed + " candidate(s)");
		numberCandidatesBeforeFilter = numberCandidatesProcessed;
		this.sortedScoredCandidateList = (SortedScoredCandidateList) this.postProcessingCandidateFilterCollection.filter(this.sortedScoredCandidateList);
		/*
		 * set number of peaks used for processing
		 */
		((ScoredCandidateList)this.sortedScoredCandidateList).setNumberPeaksUsed(((AbstractPeakList)this.globalSettings.get(VariableNames.PEAK_LIST_NAME)).getNumberPeaksUsed());
		
		this.logger.info(this.processingStatus.getNumberPreFilteredCandidates().get() + " candidate(s) were discarded before processing due to pre-filtering");
		this.logger.info(this.processingStatus.getNumberErrorCandidates().get() + " candidate(s) discarded during processing due to errors");
		this.logger.info(this.postProcessingCandidateFilterCollection.getNumberPostFilteredCandidates() + " candidate(s) discarded after processing due to post-filtering");
		this.logger.info("Stored " + this.sortedScoredCandidateList.getNumberElements() + " candidate(s)");
		
		this.processingStatus.setProcessStatusString("Processing Candidates");
		
		this.processes = null;
	}
	
	/**
	 * terminate processing thread
	 */
	public void terminate() {
		if(this.processes != null) {
			for(int i = 0; i < this.processes.length; i++) {
				if(this.processes[i] != null) {
					this.processes[i].nullify();
					this.processes[i] = null;
				}
			}
		}
		this.processes = null;
		if(this.executer == null) return;
		this.threadStoppedExternally = true;
		this.executer.shutdownNow();
	}
	
	public CandidateList getCandidateList() {
		return this.sortedScoredCandidateList;
	}

	/**
	 * init database, peaklist reader, bond energies 
	 */
	private void initialise() {
		/*
		 * set processing status object
		 * stores and returns status of metfrag processing
		 */
		this.processingStatus = new ProcessingStatus(this.globalSettings);
		this.globalSettings.set(VariableNames.PROCESS_STATUS_OBJECT_NAME, this.processingStatus);
		if(this.logger.isTraceEnabled())
			this.logger.trace(this.getClass().getName());
		try {
			if(this.logger.isTraceEnabled())
				this.logger.trace("\tinitialising database " + VariableNames.METFRAG_DATABASE_TYPE_NAME);
			//initialise database
			this.database = (IDatabase) Class.forName(ClassNames.getClassNameOfDatabase((String)this.globalSettings.get(VariableNames.METFRAG_DATABASE_TYPE_NAME))).getConstructor(Settings.class).newInstance(this.globalSettings);
			if(this.logger.isTraceEnabled())
				this.logger.trace("\tinitialising peakListReader " + VariableNames.METFRAG_PEAK_LIST_READER_NAME);
			//init peaklist reader
			this.peakListReader = (IPeakListReader) Class.forName((String)this.globalSettings.get(VariableNames.METFRAG_PEAK_LIST_READER_NAME)).getConstructor(Settings.class).newInstance(this.globalSettings);
			//init bond energies
			BondEnergies bondEnergies = null;
			//from external file if given
			if(this.globalSettings.get(VariableNames.BOND_ENERGY_FILE_PATH_NAME) != null) 
				bondEnergies = new BondEnergies((String)this.globalSettings.get(VariableNames.BOND_ENERGY_FILE_PATH_NAME));
			else //or use defaults
				bondEnergies = new BondEnergies();
			this.globalSettings.set(VariableNames.BOND_ENERGY_OBJECT_NAME, bondEnergies);
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		} catch (SecurityException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * init pre- and post-processing candidate filters
	 */
	private void initialiseCandidateFilters() {
		if(this.logger.isTraceEnabled())
			this.logger.trace(this.getClass().getName());
		/*
		 * retrieve candidate filter class names
		 */
		String[] preProcessingCandidateFilterNames = (String[])globalSettings.get(VariableNames.METFRAG_PRE_PROCESSING_CANDIDATE_FILTER_NAME);
		String[] postProcessingCandidateFilterNames = (String[])globalSettings.get(VariableNames.METFRAG_POST_PROCESSING_CANDIDATE_FILTER_NAME);
		/*
		 * initialise candidate filter arrays
		 */
		IPreProcessingCandidateFilter[] preProcessingCandidateFilter = new IPreProcessingCandidateFilter[preProcessingCandidateFilterNames.length];
		IPostProcessingCandidateFilter[] postProcessingCandidateFilter = new IPostProcessingCandidateFilter[postProcessingCandidateFilterNames.length];
		/*
		 * fill arrays with candidate filter objects
		 */
		try {
			if(this.logger.isTraceEnabled())
				this.logger.trace("\tinitialising preProcessingCandidateFilters");
			//init pre-processing filters
			for(int i = 0; i < preProcessingCandidateFilterNames.length; i++) {
				if(this.logger.isTraceEnabled())
					this.logger.trace("\t\tinitialising " + preProcessingCandidateFilterNames[i]);
				preProcessingCandidateFilter[i] = (IPreProcessingCandidateFilter) Class.forName(ClassNames.getClassNameOfPreProcessingCandidateFilter(preProcessingCandidateFilterNames[i])).getConstructor(Settings.class).newInstance(this.globalSettings);
			}
			if(this.logger.isTraceEnabled())
				this.logger.trace("\tinitialising postProcessingCandidateFilters");
			//init post-processing filters
			for(int i = 0; i < postProcessingCandidateFilterNames.length; i++) {
				if(this.logger.isTraceEnabled())
					this.logger.trace("\t\tinitialising " + postProcessingCandidateFilterNames[i]);
				postProcessingCandidateFilter[i] = (IPostProcessingCandidateFilter) Class.forName(ClassNames.getClassNameOfPostProcessingCandidateFilter(postProcessingCandidateFilterNames[i])).getConstructor(Settings.class).newInstance(this.globalSettings);
			}
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
		
		/*
		 * define the filter collections
		 * stores all filters in one collection
		 */
		this.preProcessingCandidateFilterCollection = new PreProcessingCandidateFilterCollection(preProcessingCandidateFilter);
		this.postProcessingCandidateFilterCollection = new PostProcessingCandidateFilterCollection(postProcessingCandidateFilter);
	}
	
	private void initialiseScoresGlobal(MetFragGlobalSettings globalSettings) {
		String[] score_types = (String[])globalSettings.get(VariableNames.METFRAG_SCORE_TYPES_NAME);
		for(int i = 0; i < score_types.length; i++) {
			try {
				IScoreInitialiser scoreInitialiser = (IScoreInitialiser) Class.forName(ClassNames.getClassNameOfScoreInitialiser(score_types[i])).getConstructor().newInstance();
				scoreInitialiser.initScoreParameters(globalSettings);
			} catch (InstantiationException e) {
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			} catch (InvocationTargetException e) {
				e.printStackTrace();
			} catch (NoSuchMethodException e) {
				e.printStackTrace();
			} catch (SecurityException e) {
				e.printStackTrace();
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	private void postProcessScoresGlobal(MetFragGlobalSettings globalSettings) {
		String[] score_types = (String[])globalSettings.get(VariableNames.METFRAG_SCORE_TYPES_NAME);
		for(int i = 0; i < score_types.length; i++) {
			try {
				IScoreInitialiser scoreInitialiser = (IScoreInitialiser) Class.forName(ClassNames.getClassNameOfScoreInitialiser(score_types[i])).getConstructor().newInstance();
				scoreInitialiser.postProcessScoreParameters(globalSettings);
			} catch (InstantiationException e) {
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			} catch (InvocationTargetException e) {
				e.printStackTrace();
			} catch (NoSuchMethodException e) {
				e.printStackTrace();
			} catch (SecurityException e) {
				e.printStackTrace();
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	public void nullify() {
		if(this.database != null) this.database.nullify();
		this.database = null;
		this.globalSettings = null;
		this.peakListReader = null;
		if(this.postProcessingCandidateFilterCollection != null) this.postProcessingCandidateFilterCollection.nullfiy();
		this.postProcessingCandidateFilterCollection = null;
		if(this.preProcessingCandidateFilterCollection != null) this.preProcessingCandidateFilterCollection.nullify();
		this.preProcessingCandidateFilterCollection = null;
		if(this.sortedScoredCandidateList != null) this.sortedScoredCandidateList.nullify();
		this.sortedScoredCandidateList = null;
	}
	
	public boolean isThreadStoppedExternally() {
		return this.threadStoppedExternally;
	}
	
	public void setCandidateList(CandidateList candidateList) {
		this.sortedScoredCandidateList = candidateList;
		numberCandidatesBeforeFilter = this.sortedScoredCandidateList.getNumberElements();
	}
	
	public Logger getLogger() {
		return this.logger;
	}

	public void setThreadStoppedExternally(boolean threadStoppedExternally) {
		this.threadStoppedExternally = threadStoppedExternally;
	}

	/**
	 * 
	 * @param path
	 * @param candidateList
	 * @return
	 */
	private boolean initExternalProperties(String path, CandidateList candidateList, String[] propertyValuesToScore) {
		Settings settings = new Settings();
		settings.set(VariableNames.LOCAL_DATABASE_PATH_NAME, path);
		LocalPropertyFileDatabase propertyDatabase = new LocalPropertyFileDatabase(settings);
		java.util.ArrayList<String> externalInChIKeys = new java.util.ArrayList<String>();
		java.util.ArrayList<String> externalPropertiesDefined = new java.util.ArrayList<String>();
		java.util.ArrayList<String> ids = null;
		try {
			ids = propertyDatabase.getCandidateIdentifiers();
		} catch(Exception e) {
			this.logger.error("Error: Problems reading ExternalPropertyFile: " + path);
			return false;
		}
		CandidateList externalCandidates = propertyDatabase.getCandidateByIdentifier(ids);
		for(int i = 0; i < externalCandidates.getNumberElements(); i++) {
			ICandidate externalCandidate = externalCandidates.getElement(i);
			if(!externalCandidate.getProperties().containsKey(VariableNames.INCHI_KEY_1_NAME) || externalCandidate.getProperty(VariableNames.INCHI_KEY_1_NAME) == null) {
				this.logger.error("Error: InChIKey1 field not defined for all candidate in ExternalPropertyFile: " + path);
				return false;
			}
			String externalInChIKey1 = (String)externalCandidate.getProperty(VariableNames.INCHI_KEY_1_NAME);
			if(externalInChIKeys.contains(externalInChIKey1)) {
				this.logger.error("Error: InChIKey1 " + externalInChIKey1 + " defined more than once in ExternalPropertyFile: " + path);
				return false;
			}
			else externalInChIKeys.add(externalInChIKey1);
			//try to set external values
			for(int j = 0; j < candidateList.getNumberElements(); j++) {
				ICandidate candidate = candidateList.getElement(j);
				String candidateInChIKey1 =  (String)candidate.getProperty(VariableNames.INCHI_KEY_1_NAME);
				if(externalInChIKey1.equals(candidateInChIKey1)) {
					java.util.Enumeration<?> externalKeys = externalCandidate.getProperties().keys();
					while(externalKeys.hasMoreElements()) {
						String currentKey = (String)externalKeys.nextElement();
						for(int k = 0; k < propertyValuesToScore.length; k++) {
							if(propertyValuesToScore[k].equals(currentKey)) {
								if(!externalPropertiesDefined.contains(currentKey)) externalPropertiesDefined.add(currentKey);
								Double value = null;
								try {
									value = (Double)externalCandidate.getProperty(currentKey);
								}
								catch(Exception e1) {
									try {
										value = Double.parseDouble((String)externalCandidate.getProperty(currentKey));
									}
									catch(Exception e2) {
										this.logger.error("Error: Invalid value for candidate " + externalInChIKey1 +  " for column " + currentKey + " in ExternalPropertyFile: " + path);
										return false;
									}
								}
								candidate.setProperty(currentKey, value);
							}
						}
					}
				}
			}
		}
		//set property to zero (neutral element) in case not defined
		for(int i = 0; i < candidateList.getNumberElements(); i++) {
			ICandidate candidate = candidateList.getElement(i);
			for(int j = 0; j < externalPropertiesDefined.size(); j++) {
				if(!candidate.getProperties().containsKey(externalPropertiesDefined.get(j)) || candidate.getProperty(externalPropertiesDefined.get(j)) == null) {
					candidate.setProperty(externalPropertiesDefined.get(j), 0.0);
				}
			}
		}
		return true;
	}
}
