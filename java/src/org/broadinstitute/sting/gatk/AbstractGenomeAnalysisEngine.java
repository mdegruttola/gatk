/*
 * Copyright (c) 2010 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk;

import net.sf.picard.filter.SamRecordFilter;
import net.sf.picard.reference.ReferenceSequenceFile;
import net.sf.picard.reference.IndexedFastaSequenceFile;
import net.sf.samtools.*;
import org.apache.log4j.Logger;
import org.broad.tribble.util.variantcontext.VariantContext;
import org.broadinstitute.sting.commandline.ArgumentSource;
import org.broadinstitute.sting.commandline.CommandLineUtils;
import org.broadinstitute.sting.commandline.ParsingEngine;
import org.broadinstitute.sting.gatk.arguments.GATKArgumentCollection;
import org.broadinstitute.sting.gatk.arguments.ValidationExclusion;
import org.broadinstitute.sting.gatk.datasources.sample.Sample;
import org.broadinstitute.sting.gatk.datasources.sample.SampleDataSource;
import org.broadinstitute.sting.gatk.datasources.simpleDataSources.ReferenceDataSource;
import org.broadinstitute.sting.gatk.datasources.simpleDataSources.ReferenceOrderedDataSource;
import org.broadinstitute.sting.gatk.datasources.simpleDataSources.SAMDataSource;
import org.broadinstitute.sting.gatk.datasources.simpleDataSources.SAMReaderID;
import org.broadinstitute.sting.gatk.filters.FilterManager;
import org.broadinstitute.sting.gatk.filters.ReadGroupBlackListFilter;
import org.broadinstitute.sting.gatk.filters.SamRecordHeaderFilter;
import org.broadinstitute.sting.gatk.io.stubs.Stub;
import org.broadinstitute.sting.gatk.refdata.tracks.RMDTrack;
import org.broadinstitute.sting.gatk.refdata.tracks.builders.RMDTrackBuilder;
import org.broadinstitute.sting.gatk.refdata.utils.RMDIntervalGenerator;
import org.broadinstitute.sting.utils.*;
import org.broadinstitute.sting.utils.exceptions.UserException;
import org.broadinstitute.sting.utils.interval.IntervalMergingRule;
import org.broadinstitute.sting.utils.interval.IntervalUtils;
import org.broadinstitute.sting.utils.text.XReadLines;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;

/**
 * Parses a GATKArgumentCollection into various gatk objects and then executes() some function.
 */
public abstract class AbstractGenomeAnalysisEngine {

    /**
     * The GATK command-line argument parsing code.
     */
    private ParsingEngine parsingEngine;

    /**
     * The genomeLocParser can create and parse GenomeLocs.
     */
    private GenomeLocParser genomeLocParser;

    /**
     * Accessor for sharded read data.
     */
    private SAMDataSource readsDataSource = null;

    /**
     * Needs to be 
     * @return
     */
    public ReferenceDataSource getReferenceDataSource() {
        return referenceDataSource;
    }

    public GenomeLocParser getGenomeLocParser() {
        return genomeLocParser;
    }

    /**
     * Accessor for sharded reference data.
     */
    private ReferenceDataSource referenceDataSource = null;

    /**
     * Accessor for sample metadata
     */
    private SampleDataSource sampleDataSource = null;

    /**
     * Accessor for sharded reference-ordered data.
     */
    private List<ReferenceOrderedDataSource> rodDataSources;

    // our argument collection
    private GATKArgumentCollection argCollection;

    /**
     * Collection of inputs used by the engine.
     */
    private Map<ArgumentSource, Object> inputs = new HashMap<ArgumentSource, Object>();

    /**
     * Collection of intervals used by the engine.
     */
    private GenomeLocSortedSet intervals = null;

    /**
     * Collection of outputs used by the engine.
     */
    private Collection<Stub<?>> outputs = new ArrayList<Stub<?>>();

    /**
     * Collection of the filters applied to the input data.
     */
    private Collection<SamRecordFilter> filters;

    /**
     * our log, which we want to capture anything from this class
     */
    private static Logger logger = Logger.getLogger(AbstractGenomeAnalysisEngine.class);

    /**
     * Manage lists of filters.
     */
    private final FilterManager filterManager = new FilterManager();

    private Date startTime = null; // the start time for execution

    public void setParser(ParsingEngine parsingEngine) {
        this.parsingEngine = parsingEngine;    
    }

    /**
     * Explicitly set the GenomeLocParser, for unit testing.
     * @param genomeLocParser GenomeLocParser to use.
     */
    public void setGenomeLocParser(GenomeLocParser genomeLocParser) {
        this.genomeLocParser = genomeLocParser;
    }

    /**
     * Actually run the engine.
     * @return the value of this traversal.
     */
    public abstract Object execute();

    protected abstract boolean flashbackData();
    protected abstract boolean includeReadsWithDeletionAtLoci();
    protected abstract boolean generateExtendedEvents();

    /**
     * Sets the start time when the execute() function was last called
     * @param startTime the start time when the execute() function was last called
     */
    protected void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    /**
     * @return the start time when the execute() function was last called
     */
    public Date getStartTime() {
        return startTime;
    }

    /**
     * Setup the intervals to be processed
     */
    protected void initializeIntervals() {

        // return if no interval arguments at all
        if ((argCollection.intervals == null) && (argCollection.excludeIntervals == null) && (argCollection.RODToInterval == null))
            return;

        // if '-L all' was specified, verify that it was the only -L specified and return if so.
        if(argCollection.intervals != null) {
            for(String interval: argCollection.intervals) {
                if(interval.trim().equals("all")) {
                    if(argCollection.intervals.size() > 1)
                        throw new UserException("'-L all' was specified along with other intervals or interval lists; the GATK cannot combine '-L all' with other intervals.");

                    // '-L all' was specified and seems valid.  Return.
                    return;
                }
            }
        }

        // if include argument isn't given, create new set of all possible intervals
        GenomeLocSortedSet includeSortedSet = (argCollection.intervals == null && argCollection.RODToInterval == null ?
                GenomeLocSortedSet.createSetFromSequenceDictionary(this.referenceDataSource.getReference().getSequenceDictionary()) :
                loadIntervals(argCollection.intervals,
                        argCollection.intervalMerging,
                        genomeLocParser.mergeIntervalLocations(checkRODToIntervalArgument(),argCollection.intervalMerging)));

        // if no exclude arguments, can return parseIntervalArguments directly
        if (argCollection.excludeIntervals == null)
            intervals = includeSortedSet;

            // otherwise there are exclude arguments => must merge include and exclude GenomeLocSortedSets
        else {
            GenomeLocSortedSet excludeSortedSet = loadIntervals(argCollection.excludeIntervals, argCollection.intervalMerging, null);
            intervals = includeSortedSet.subtractRegions(excludeSortedSet);

            // logging messages only printed when exclude (-XL) arguments are given
            long toPruneSize = includeSortedSet.coveredSize();
            long toExcludeSize = excludeSortedSet.coveredSize();
            long intervalSize = intervals.coveredSize();
            logger.info(String.format("Initial include intervals span %d loci; exclude intervals span %d loci", toPruneSize, toExcludeSize));
            logger.info(String.format("Excluding %d loci from original intervals (%.2f%% reduction)",
                    toPruneSize - intervalSize, (toPruneSize - intervalSize) / (0.01 * toPruneSize)));
        }
    }

    /**
     * Loads the intervals relevant to the current execution
     * @param argList String representation of arguments; might include 'all', filenames, intervals in samtools
     *                notation, or a combination of the
     * @param mergingRule Technique to use when merging interval data.
     * @param additionalIntervals a list of additional intervals to add to the returned set.  Can be null.
     * @return A sorted, merged list of all intervals specified in this arg list.
     */
    private GenomeLocSortedSet loadIntervals(List<String> argList,
                                             IntervalMergingRule mergingRule,
                                             List<GenomeLoc> additionalIntervals) {

        return IntervalUtils.sortAndMergeIntervals(genomeLocParser,IntervalUtils.mergeListsBySetOperator(additionalIntervals,
                IntervalUtils.parseIntervalArguments(genomeLocParser,argList,
                        this.getArguments().unsafe != ValidationExclusion.TYPE.ALLOW_EMPTY_INTERVAL_LIST),
                argCollection.BTIMergeRule),
                mergingRule);
    }

    /**
     * if we have a ROD specified as a 'rodToIntervalTrackName', convert its records to RODs
     * @return ROD intervals as GenomeLocs
     */
    private List<GenomeLoc> checkRODToIntervalArgument() {
        Map<String, ReferenceOrderedDataSource> rodNames = RMDIntervalGenerator.getRMDTrackNames(rodDataSources);
        // Do we have any RODs that overloaded as interval lists with the 'rodToIntervalTrackName' flag?
        List<GenomeLoc> ret = new ArrayList<GenomeLoc>();
        if (rodNames != null && argCollection.RODToInterval != null) {
            String rodName = argCollection.RODToInterval;

            // check to make sure we have a rod of that name
            if (!rodNames.containsKey(rodName))
                throw new UserException.CommandLineException("--rodToIntervalTrackName (-BTI) was passed the name '"+rodName+"', which wasn't given as a ROD name in the -B option");

            for (String str : rodNames.keySet())
                if (str.equals(rodName)) {
                    logger.info("Adding interval list from track (ROD) named " + rodName);
                    RMDIntervalGenerator intervalGenerator = new RMDIntervalGenerator(rodNames.get(str).getReferenceOrderedData());
                    ret.addAll(intervalGenerator.toGenomeLocList());
                }
        }
        return ret;
    }

    /**
     * Add additional, externally managed IO streams for inputs.
     *
     * @param argumentSource Field into which to inject the value.
     * @param value          Instance to inject.
     */
    public void addInput(ArgumentSource argumentSource, Object value) {
        inputs.put(argumentSource, value);
    }

    /**
     * Add additional, externally managed IO streams for output.
     *
     * @param stub Instance to inject.
     */
    public void addOutput(Stub<?> stub) {
        outputs.add(stub);
    }

    /**
     * Gets the tags associated with a given object.
     * @param key Key for which to find a tag.
     * @return List of tags associated with this key.
     */
    public List<String> getTags(Object key)  {
        return parsingEngine.getTags(key);
    }

    /**
     * Gets a list of the filters to associate with the engine.  Will NOT initialize the engine with this filters;
     * the caller must handle that directly.
     * @return A collection of available filters.
     */
    public Collection<SamRecordFilter> createFilters() {
        Set<SamRecordFilter> filters = new HashSet<SamRecordFilter>();
        if (this.getArguments().readGroupBlackList != null && this.getArguments().readGroupBlackList.size() > 0)
            filters.add(new ReadGroupBlackListFilter(this.getArguments().readGroupBlackList));
        for(String filterName: this.getArguments().readFilters)
            filters.add(this.getFilterManager().createByName(filterName));
        return Collections.unmodifiableSet(filters);
    }

    protected void initializeDataSources() {
        logger.info("Strictness is " + argCollection.strictnessLevel);

        validateSuppliedReference();
        referenceDataSource = openReferenceSequenceFile(argCollection.referenceFile);

        validateSuppliedReads();
        readsDataSource = createReadsDataSource(genomeLocParser, referenceDataSource.getReference());

        sampleDataSource = new SampleDataSource(getSAMFileHeader(), argCollection.sampleFiles);

        for (SamRecordFilter filter : filters)
            if (filter instanceof SamRecordHeaderFilter)
                ((SamRecordHeaderFilter)filter).setHeader(this.getSAMFileHeader());
        
        sampleDataSource = new SampleDataSource(getSAMFileHeader(), argCollection.sampleFiles);

        // set the sequence dictionary of all of Tribble tracks to the sequence dictionary of our reference
        RMDTrackBuilder manager = new RMDTrackBuilder(referenceDataSource.getReference().getSequenceDictionary(),genomeLocParser,argCollection.unsafe);
        List<RMDTrack> tracks = manager.getReferenceMetaDataSources(this,argCollection);
        validateSuppliedReferenceOrderedData(tracks);

        // validate all the sequence dictionaries against the reference
        validateSourcesAgainstReference(readsDataSource, referenceDataSource.getReference(), tracks, manager);

        rodDataSources = getReferenceOrderedDataSources(tracks);
    }

    /**
     * Gets a unique identifier for the reader sourcing this read.
     * @param read Read to examine.
     * @return A unique identifier for the source file of this read.  Exception if not found.
     */
    public SAMReaderID getReaderIDForRead(final SAMRecord read) {
        return getReadsDataSource().getReaderID(read);
    }

    /**
     * Gets the source file for this read.
     * @param id Unique identifier determining which input file to use.
     * @return The source filename for this read.
     */
    public File getSourceFileForReaderID(final SAMReaderID id) {
        return getReadsDataSource().getSAMFile(id);
    }

    /**
     * Returns sets of samples present in the (merged) input SAM stream, grouped by readers (i.e. underlying
     * individual bam files). For instance: if GATK is run with three input bam files (three -I arguments), then the list
     * returned by this method will contain 3 elements (one for each reader), with each element being a set of sample names
     * found in the corresponding bam file.
     *
     * @return Sets of samples in the merged input SAM stream, grouped by readers
     */
    public List<Set<String>> getSamplesByReaders() {
        List<SAMReaderID> readers = getReadsDataSource().getReaderIDs();

        List<Set<String>> sample_sets = new ArrayList<Set<String>>(readers.size());

        for (SAMReaderID r : readers) {

            Set<String> samples = new HashSet<String>(1);
            sample_sets.add(samples);

            for (SAMReadGroupRecord g : getReadsDataSource().getHeader(r).getReadGroups()) {
                samples.add(g.getSample());
            }
        }

        return sample_sets;

    }

    /**
     * Returns sets of libraries present in the (merged) input SAM stream, grouped by readers (i.e. underlying
     * individual bam files). For instance: if GATK is run with three input bam files (three -I arguments), then the list
     * returned by this method will contain 3 elements (one for each reader), with each element being a set of library names
     * found in the corresponding bam file.
     *
     * @return Sets of libraries present in the (merged) input SAM stream, grouped by readers
     */
    public List<Set<String>> getLibrariesByReaders() {


        List<SAMReaderID> readers = getReadsDataSource().getReaderIDs();

        List<Set<String>> lib_sets = new ArrayList<Set<String>>(readers.size());

        for (SAMReaderID r : readers) {

            Set<String> libs = new HashSet<String>(2);
            lib_sets.add(libs);

            for (SAMReadGroupRecord g : getReadsDataSource().getHeader(r).getReadGroups()) {
                libs.add(g.getLibrary());
            }
        }

        return lib_sets;

    }

    /**
     * Returns a mapping from original input files to their (merged) read group ids
     *
     * @return the mapping
     */
    public Map<File, Set<String>> getFileToReadGroupIdMapping() {
        // populate the file -> read group mapping
        Map<File, Set<String>> fileToReadGroupIdMap = new HashMap<File, Set<String>>();
        for (SAMReaderID id: getReadsDataSource().getReaderIDs()) {
            Set<String> readGroups = new HashSet<String>(5);

            for (SAMReadGroupRecord g : getReadsDataSource().getHeader(id).getReadGroups()) {
                if (getReadsDataSource().hasReadGroupCollisions()) {
                    // Check if there were read group clashes.
                    // If there were, use the SamFileHeaderMerger to translate from the
                    // original read group id to the read group id in the merged stream
                    readGroups.add(getReadsDataSource().getReadGroupId(id,g.getReadGroupId()));
                } else {
                    // otherwise, pass through the unmapped read groups since this is what Picard does as well
                    readGroups.add(g.getReadGroupId());
                }
            }

            fileToReadGroupIdMap.put(getReadsDataSource().getSAMFile(id),readGroups);
        }

        return fileToReadGroupIdMap;
    }

    /**
     * **** UNLESS YOU HAVE GOOD REASON TO, DO NOT USE THIS METHOD; USE getFileToReadGroupIdMapping() INSTEAD ****
     *
     * Returns sets of (remapped) read groups in input SAM stream, grouped by readers (i.e. underlying
     * individual bam files). For instance: if GATK is run with three input bam files (three -I arguments), then the list
     * returned by this method will contain 3 elements (one for each reader), with each element being a set of remapped read groups
     * (i.e. as seen by read.getReadGroup().getReadGroupId() in the merged stream) that come from the corresponding bam file.
     *
     * @return sets of (merged) read group ids in order of input bams
     */
    public List<Set<String>> getMergedReadGroupsByReaders() {


        List<SAMReaderID> readers = getReadsDataSource().getReaderIDs();

        List<Set<String>> rg_sets = new ArrayList<Set<String>>(readers.size());

        for (SAMReaderID r : readers) {

            Set<String> groups = new HashSet<String>(5);
            rg_sets.add(groups);

            for (SAMReadGroupRecord g : getReadsDataSource().getHeader(r).getReadGroups()) {
                if (getReadsDataSource().hasReadGroupCollisions()) { // Check if there were read group clashes with hasGroupIdDuplicates and if so:
                    // use HeaderMerger to translate original read group id from the reader into the read group id in the
                    // merged stream, and save that remapped read group id to associate it with specific reader
                    groups.add(getReadsDataSource().getReadGroupId(r, g.getReadGroupId()));
                } else {
                    // otherwise, pass through the unmapped read groups since this is what Picard does as well
                    groups.add(g.getReadGroupId());
                }
            }
        }

        return rg_sets;

    }

    protected DownsamplingMethod getDownsamplingMethod() {
        DownsamplingMethod method;
        if(argCollection.getDownsamplingMethod() != null)
            method = argCollection.getDownsamplingMethod();
        else
            method = argCollection.getDefaultDownsamplingMethod();
        return method;
    }

    protected void validateSuppliedReads() {
    }

    protected void validateSuppliedReference() {
    }

    protected void validateSuppliedReferenceOrderedData(List<RMDTrack> rods) {
    }

    /**
     * Now that all files are open, validate the sequence dictionaries of the reads vs. the reference vrs the reference ordered data (if available).
     *
     * @param reads     Reads data source.
     * @param reference Reference data source.
     * @param tracks    a collection of the reference ordered data tracks
     */
    private void validateSourcesAgainstReference(SAMDataSource reads, ReferenceSequenceFile reference, Collection<RMDTrack> tracks, RMDTrackBuilder manager) {
        if ((reads.isEmpty() && (tracks == null || tracks.isEmpty())) || reference == null )
            return;

        // Compile a set of sequence names that exist in the reference file.
        SAMSequenceDictionary referenceDictionary = reference.getSequenceDictionary();

        if (!reads.isEmpty()) {
            // Compile a set of sequence names that exist in the BAM files.
            SAMSequenceDictionary readsDictionary = reads.getHeader().getSequenceDictionary();

            Set<String> readsSequenceNames = new TreeSet<String>();
            for (SAMSequenceRecord dictionaryEntry : readsDictionary.getSequences())
                readsSequenceNames.add(dictionaryEntry.getSequenceName());


            if (readsSequenceNames.size() == 0) {
                logger.info("Reads file is unmapped.  Skipping validation against reference.");
                return;
            }

            // compare the reads to the reference
            SequenceDictionaryUtils.validateDictionaries(logger, getArguments().unsafe, "reads", readsDictionary, "reference", referenceDictionary);
        }

        // compare the tracks to the reference, if they have a sequence dictionary
        for (RMDTrack track : tracks)
            manager.validateTrackSequenceDictionary(track.getName(),track.getSequenceDictionary(),referenceDictionary);
    }

    /**
     * Gets a data source for the given set of reads.
     *
     * @return A data source for the given set of reads.
     */
    private SAMDataSource createReadsDataSource(GenomeLocParser genomeLocParser, IndexedFastaSequenceFile refReader) {
        DownsamplingMethod method = getDownsamplingMethod();

        return new SAMDataSource(
                unpackBAMFileList(argCollection.samFiles),
                genomeLocParser,
                argCollection.useOriginalBaseQualities,
                argCollection.strictnessLevel,
                argCollection.readBufferSize,
                method,
                new ValidationExclusion(Arrays.asList(argCollection.unsafe)),
                filters,
                includeReadsWithDeletionAtLoci(),
                generateExtendedEvents(),
                argCollection.BAQMode, refReader);
    }

    /**
     * Opens a reference sequence file paired with an index.
     *
     * @param refFile Handle to a reference sequence file.  Non-null.
     * @return A thread-safe file wrapper.
     */
    private ReferenceDataSource openReferenceSequenceFile(File refFile) {
        ReferenceDataSource ref = new ReferenceDataSource(refFile);
        genomeLocParser = new GenomeLocParser(ref.getReference());
        return ref;
    }

    /**
     * Open the reference-ordered data sources.
     *
     * @param rods the reference order data to execute using
     * @return A list of reference-ordered data sources.
     */
    private List<ReferenceOrderedDataSource> getReferenceOrderedDataSources(List<RMDTrack> rods) {
        List<ReferenceOrderedDataSource> dataSources = new ArrayList<ReferenceOrderedDataSource>();
        for (RMDTrack rod : rods)
            dataSources.add(new ReferenceOrderedDataSource(referenceDataSource.getReference().getSequenceDictionary(),
                                                           genomeLocParser,
                                                           argCollection.unsafe,
                                                           rod,
                                                           flashbackData()));
        return dataSources;
    }

    /**
     * Returns the SAM File Header from the input reads' data source file
     * @return the SAM File Header from the input reads' data source file
     */
    public SAMFileHeader getSAMFileHeader() {
        return readsDataSource.getHeader();
    }

    /**
     * Returns the unmerged SAM file header for an individual reader.
     * @param reader The reader.
     * @return Header for that reader.
     */
    public SAMFileHeader getSAMFileHeader(SAMReaderID reader) {
        return readsDataSource.getHeader(reader);
    }

    /**
     * Returns data source object encapsulating all essential info and handlers used to traverse
     * reads; header merger, individual file readers etc can be accessed through the returned data source object.
     *
     * @return the reads data source
     */
    public SAMDataSource getReadsDataSource() {
        return this.readsDataSource;
    }



    /**
     * Sets the collection of GATK main application arguments.
     *
     * @param argCollection the GATK argument collection
     */
    public void setArguments(GATKArgumentCollection argCollection) {
        this.argCollection = argCollection;
    }

    /**
     * Gets the collection of GATK main application arguments.
     *
     * @return the GATK argument collection
     */
    public GATKArgumentCollection getArguments() {
        return this.argCollection;
    }

    /**
     * Get the list of intervals passed to the engine.
     * @return List of intervals.
     */
    public GenomeLocSortedSet getIntervals() {
        return this.intervals;
    }

    /**
     * Gets the list of filters employed by this engine.
     * @return Collection of filters (actual instances) used by this engine.
     */
    public Collection<SamRecordFilter> getFilters() {
        return this.filters;
    }

    /**
     * Sets the list of filters employed by this engine.
     * @param filters Collection of filters (actual instances) used by this engine.
     */
    public void setFilters(Collection<SamRecordFilter> filters) {
        this.filters = filters;
    }

    /**
     * Gets the filter manager for this engine.
     * @return filter manager for this engine.
     */
    protected FilterManager getFilterManager() {
        return filterManager;
    }

    /**
     * Gets the input sources for this engine.
     * @return input sources for this engine.
     */
    protected Map<ArgumentSource, Object> getInputs() {
        return inputs;
    }

    /**
     * Gets the output stubs for this engine.
     * @return output stubs for this engine.
     */
    protected Collection<Stub<?>> getOutputs() {
        return outputs;
    }

    /**
     * Returns data source objects encapsulating all rod data;
     * individual rods can be accessed through the returned data source objects.
     *
     * @return the rods data sources
     */
    public List<ReferenceOrderedDataSource> getRodDataSources() {
        return this.rodDataSources;
    }

    /**
     * Gets cumulative metrics about the entire run to this point.
     * @return cumulative metrics about the entire run.
     */
    public ReadMetrics getCumulativeMetrics() {
        return readsDataSource == null ? null : readsDataSource.getCumulativeReadMetrics();
    }

    /**
     * Unpack the bam files to be processed, given a list of files.  That list of files can
     * itself contain entries which are lists of other files to be read (note: you cannot have lists of lists of lists)
     *
     * @param inputFiles a list of files that represent either bam files themselves, or a file containing a list of bam files to process
     *
     * @return a flattened list of the bam files provided
     */
    private List<SAMReaderID> unpackBAMFileList( List<File> inputFiles ) {
        List<SAMReaderID> unpackedReads = new ArrayList<SAMReaderID>();
        for( File inputFile: inputFiles ) {
            if (inputFile.getName().toLowerCase().endsWith(".list") ) {
                try {
                    for(String fileName : new XReadLines(inputFile))
                        unpackedReads.add(new SAMReaderID(new File(fileName),getTags(inputFile)));
                }
                catch( FileNotFoundException ex ) {
                    throw new UserException.CouldNotReadInputFile(inputFile, "Unable to find file while unpacking reads", ex);
                }
            }
            else if(inputFile.getName().toLowerCase().endsWith(".bam")) {
                unpackedReads.add( new SAMReaderID(inputFile,getTags(inputFile)) );
            }
            else if(inputFile.getName().equals("-")) {
                unpackedReads.add(new SAMReaderID(new File("/dev/stdin"),Collections.<String>emptyList()));
            }
            else {
                throw new UserException.CommandLineException(String.format("The GATK reads argument (-I) supports only BAM files with the .bam extension and lists of BAM files " +
                        "with the .list extension, but the file %s has neither extension.  Please ensure that your BAM file or list " +
                        "of BAM files is in the correct format, update the extension, and try again.",inputFile.getName()));
            }
        }
        return unpackedReads;
    }

    public SampleDataSource getSampleMetadata() {
        return this.sampleDataSource;
    }

    /**
     * Get a sample by its ID
     * If an alias is passed in, return the main sample object
     * @param id sample id
     * @return sample Object with this ID
     */
    public Sample getSampleById(String id) {
        return sampleDataSource.getSampleById(id);
    }

    /**
     * Get the sample for a given read group
     * Must first look up ID for read group
     * @param readGroup of sample
     * @return sample object with ID from the read group
     */
    public Sample getSampleByReadGroup(SAMReadGroupRecord readGroup) {
        return sampleDataSource.getSampleByReadGroup(readGroup);
    }

    /**
     * Get a sample for a given read
     * Must first look up read group, and then sample ID for that read group
     * @param read of sample
     * @return sample object of this read
     */
    public Sample getSampleByRead(SAMRecord read) {
        return getSampleByReadGroup(read.getReadGroup());
    }

    /**
     * Get number of sample objects
     * @return size of samples map
     */
    public int sampleCount() {
        return sampleDataSource.sampleCount();
    }

    /**
     * Return all samples with a given family ID
     * Note that this isn't terribly efficient (linear) - it may be worth adding a new family ID data structure for this
     * @param familyId family ID
     * @return Samples with the given family ID
     */
    public Set<Sample> getFamily(String familyId) {
        return sampleDataSource.getFamily(familyId);
    }

    /**
     * Returns all children of a given sample
     * See note on the efficiency of getFamily() - since this depends on getFamily() it's also not efficient
     * @param sample parent sample
     * @return children of the given sample
     */
    public Set<Sample> getChildren(Sample sample) {
        return sampleDataSource.getChildren(sample);
    }

    /**
     * Gets all the samples
     * @return
     */
    public Collection<Sample> getSamples() {
        return sampleDataSource.getSamples();
    }

    /**
     * Takes a list of sample names and returns their corresponding sample objects
     *
     * @param sampleNameList List of sample names
     * @return Corresponding set of samples
     */
    public Set<Sample> getSamples(Collection<String> sampleNameList) {
	return sampleDataSource.getSamples(sampleNameList);
    }


    /**
     * Returns a set of samples that have any value (which could be null) for a given property
     * @param key Property key
     * @return Set of samples with the property
     */
    public Set<Sample> getSamplesWithProperty(String key) {
        return sampleDataSource.getSamplesWithProperty(key);
    }

    /**
     * Returns a set of samples that have a property with a certain value
     * Value must be a string for now - could add a similar method for matching any objects in the future
     *
     * @param key Property key
     * @param value String property value
     * @return Set of samples that match key and value
     */
    public Set<Sample> getSamplesWithProperty(String key, String value) {
        return sampleDataSource.getSamplesWithProperty(key, value);

    }

    /**
     * Returns a set of sample objects for the sample names in a variant context
     *
     * @param context Any variant context
     * @return a set of the sample objects
     */
    public Set<Sample> getSamplesByVariantContext(VariantContext context) {
        Set<Sample> samples = new HashSet<Sample>();
        for (String sampleName : context.getSampleNames()) {
            samples.add(sampleDataSource.getOrCreateSample(sampleName));
        }
        return samples;
    }

    /**
     * Returns all samples that were referenced in the SAM file
     */
    public Set<Sample> getSAMFileSamples() {
        return sampleDataSource.getSAMFileSamples();
    }

    /**
     * Return a subcontext restricted to samples with a given property key/value
     * Gets the sample names from key/value and relies on VariantContext.subContextFromGenotypes for the filtering
     * @param context VariantContext to filter
     * @param key property key
     * @param value property value (must be string)
     * @return subcontext
     */
    public VariantContext subContextFromSampleProperty(VariantContext context, String key, String value) {
        return sampleDataSource.subContextFromSampleProperty(context, key, value);
    }

    public Map<String,String> getApproximateCommandLineArguments(Object... argumentProviders) {
        return CommandLineUtils.getApproximateCommandLineArguments(parsingEngine,argumentProviders);
    }

    public String createApproximateCommandLineArgumentString(Object... argumentProviders) {
        return CommandLineUtils.createApproximateCommandLineArgumentString(parsingEngine,argumentProviders);
    }
}
