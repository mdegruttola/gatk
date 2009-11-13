package org.broadinstitute.sting.utils.genotype.glf;

import net.sf.samtools.SAMRecord;
import org.broadinstitute.sting.utils.GenomeLoc;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.utils.genotype.*;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;


/**
 * @author aaron
 *         <p/>
 *         Class GLFGenotypeCall
 *         <p/>
 *         The implementation of the genotype interface, specific to GLF
 */
public class GLFGenotypeCall implements Genotype, ReadBacked, LikelihoodsBacked {
    private final char mRefBase;
    private final GenomeLoc mLocation;

    private List<SAMRecord> mReads;
    private double[] mLikelihoods;

    private double mNegLog10PError;
    private String mGenotype;

    /**
     * Generate a single sample genotype object, containing everything we need to represent calls out of a genotyper object
     *
     */
    public GLFGenotypeCall(char ref, GenomeLoc loc) {
        mRefBase = ref;
        mGenotype = Utils.dupString(ref, 2);

        // fill in empty data
        mLocation = loc;
        mLikelihoods = new double[10];
        Arrays.fill(mLikelihoods, Double.MIN_VALUE);
        mReads = new ArrayList<SAMRecord>();
        mNegLog10PError = Double.MIN_VALUE;
    }

    public void setReads(List<SAMRecord> reads) {
        mReads = new ArrayList<SAMRecord>(reads);
    }

    public void setLikelihoods(double[] likelihoods) {
        mLikelihoods = likelihoods;
    }

    public void setNegLog10PError(double negLog10PError) {
        mNegLog10PError = negLog10PError;
    }

    public void setGenotype(String genotype) {
        mGenotype = genotype;
    }

    @Override
    public boolean equals(Object other) {
        if (other == null || !(other instanceof GLFGenotypeCall))
            return false;
        return (this.mRefBase == ((GLFGenotypeCall)other).mRefBase);
    }

    public String toString() {
        return String.format("%s ref=%s depth=%d negLog10PError=%.2f",
                             getLocation(), mRefBase, mReads.size(), getNegLog10PError());
    }

    /**
     * get the confidence we have
     *
     * @return get the one minus error value
     */
    public double getNegLog10PError() {
        return mNegLog10PError;
    }

    /**
     * get the bases that represent this
     *
     * @return the bases, as a string
     */
    public String getBases() {
        return Character.toString(mRefBase);
    }

    /**
     * get the ploidy
     *
     * @return the ploidy value
     */
    public int getPloidy() {
        return 2;
    }

    /**
     * Returns true if both observed alleles are the same (regardless of whether they are ref or alt)
     *
     * @return true if we're homozygous, false otherwise
     */
    public boolean isHom() {
        return true;
    }

    /**
     * Returns true if observed alleles differ (regardless of whether they are ref or alt)
     *
     * @return true if we're het, false otherwise
     */
    public boolean isHet() {
        return !isHom();
    }

    /**
     *
     * @return return this genotype as a variant
     */
    public Variation toVariation(char ref) {
        throw new UnsupportedOperationException("GLF call doesn't support conversion to Variation");
    }

    /**
     * Location of this genotype on the reference (on the forward strand). If the allele is insertion/deletion, the first inserted/deleted base
     * is located right <i>after</i> the specified location
     *
     * @return position on the genome wrapped in GenomeLoc object
     */
    public GenomeLoc getLocation() {
        return this.mLocation;
    }

    /**
     * returns true if the genotype is a point genotype, false if it's a indel / deletion
     *
     * @return true is a SNP
     */
    public boolean isPointGenotype() {
        return true;
    }

    /**
     * given the reference, are we a variant? (non-ref)
     *
     * @param ref the reference base or bases
     *
     * @return true if we're a variant
     */
    public boolean isVariant(char ref) {
        return !Utils.dupString(mRefBase, 2).equals(mGenotype);
    }

    /**
     * get the SAM records that back this genotype call
     *
     * @return a list of SAM records
     */
    public List<SAMRecord> getReads() {
        return mReads;
    }

    /**
     * get the count of reads
     *
     * @return the number of reads we're backed by
     */
    public int getReadCount() {
        return mReads.size();
    }

    /**
     * get the reference character
     *
     * @return the reference character
     */
    public char getReference() {
        return mRefBase;
    }

    /**
     * get the posteriors
     *
     * @return the posteriors
     */
    public double[] getLikelihoods() {
        return mLikelihoods;
    }

}