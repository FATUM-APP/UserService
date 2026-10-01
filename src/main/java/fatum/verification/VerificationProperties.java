package fatum.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every threshold of the identity verification process.
 *
 * <p>The business rules are configuration, not code: the bands, the number of retries, the weights of
 * each signal and the AWS analyzers can all be tuned per environment without a release.</p>
 */
@ConfigurationProperties(prefix = "fatum.verification")
public class VerificationProperties {

    /** Turns the whole pipeline on or off; when off the endpoints answer 503. */
    private boolean enabled = true;

    /** Attempts a user gets before the case escalates. */
    private int maxAttempts = 3;

    /** Score at or above which the user is verified. */
    private double verifiedThreshold = 80d;

    /** Score at or above which the user keeps retrying instead of being rejected. */
    private double manualThreshold = 30d;

    /**
     * Score below which a retry is considered a hard rejection. Two consecutive hard rejections end
     * the process immediately, as the business asked.
     */
    private double hardRejectThreshold = 20d;

    /** Bedrock risk at or above which the document is treated as forged. */
    private double fraudRiskThreshold = 70d;

    /** Face similarity below which the document is considered not to belong to the user. */
    private double minFaceMatch = 20d;

    /** Similarity required to accept a new profile picture against the liveness reference. */
    private double profilePhotoChangeThreshold = 80d;

    /** Similarity Rekognition has to report for two faces to be considered the same person. */
    private double faceSimilarityThreshold = 80d;

    private Weights weights = new Weights();

    private Analyzers analyzers = new Analyzers();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public double getVerifiedThreshold() {
        return verifiedThreshold;
    }

    public void setVerifiedThreshold(double verifiedThreshold) {
        this.verifiedThreshold = verifiedThreshold;
    }

    public double getManualThreshold() {
        return manualThreshold;
    }

    public void setManualThreshold(double manualThreshold) {
        this.manualThreshold = manualThreshold;
    }

    public double getHardRejectThreshold() {
        return hardRejectThreshold;
    }

    public void setHardRejectThreshold(double hardRejectThreshold) {
        this.hardRejectThreshold = hardRejectThreshold;
    }

    public double getFraudRiskThreshold() {
        return fraudRiskThreshold;
    }

    public void setFraudRiskThreshold(double fraudRiskThreshold) {
        this.fraudRiskThreshold = fraudRiskThreshold;
    }

    public double getMinFaceMatch() {
        return minFaceMatch;
    }

    public void setMinFaceMatch(double minFaceMatch) {
        this.minFaceMatch = minFaceMatch;
    }

    public double getProfilePhotoChangeThreshold() {
        return profilePhotoChangeThreshold;
    }

    public void setProfilePhotoChangeThreshold(double profilePhotoChangeThreshold) {
        this.profilePhotoChangeThreshold = profilePhotoChangeThreshold;
    }

    public double getFaceSimilarityThreshold() {
        return faceSimilarityThreshold;
    }

    public void setFaceSimilarityThreshold(double faceSimilarityThreshold) {
        this.faceSimilarityThreshold = faceSimilarityThreshold;
    }

    public Weights getWeights() {
        return weights;
    }

    public void setWeights(Weights weights) {
        this.weights = weights;
    }

    public Analyzers getAnalyzers() {
        return analyzers;
    }

    public void setAnalyzers(Analyzers analyzers) {
        this.analyzers = analyzers;
    }

    /**
     * Relative importance of every signal. A signal that could not be evaluated is removed from the
     * calculation and the remaining weights are re-normalised, so a disabled analyzer does not push
     * every user to rejection.
     */
    public static class Weights {

        private double documentMatch = 0.35d;

        private double documentLivenessMatch = 0.30d;

        private double profileLivenessMatch = 0.20d;

        private double authenticity = 0.15d;

        public double getDocumentMatch() {
            return documentMatch;
        }

        public void setDocumentMatch(double documentMatch) {
            this.documentMatch = documentMatch;
        }

        public double getDocumentLivenessMatch() {
            return documentLivenessMatch;
        }

        public void setDocumentLivenessMatch(double documentLivenessMatch) {
            this.documentLivenessMatch = documentLivenessMatch;
        }

        public double getProfileLivenessMatch() {
            return profileLivenessMatch;
        }

        public void setProfileLivenessMatch(double profileLivenessMatch) {
            this.profileLivenessMatch = profileLivenessMatch;
        }

        public double getAuthenticity() {
            return authenticity;
        }

        public void setAuthenticity(double authenticity) {
            this.authenticity = authenticity;
        }
    }

    /** Settings of the AWS analyzers. */
    public static class Analyzers {

        private boolean textractEnabled = true;

        private boolean rekognitionEnabled = true;

        private boolean bedrockEnabled = true;

        private String bedrockModelId = "anthropic.claude-3-5-sonnet-20241022-v2:0";

        private int bedrockMaxTokens = 800;

        private double bedrockTemperature = 0d;

        public boolean isTextractEnabled() {
            return textractEnabled;
        }

        public void setTextractEnabled(boolean textractEnabled) {
            this.textractEnabled = textractEnabled;
        }

        public boolean isRekognitionEnabled() {
            return rekognitionEnabled;
        }

        public void setRekognitionEnabled(boolean rekognitionEnabled) {
            this.rekognitionEnabled = rekognitionEnabled;
        }

        public boolean isBedrockEnabled() {
            return bedrockEnabled;
        }

        public void setBedrockEnabled(boolean bedrockEnabled) {
            this.bedrockEnabled = bedrockEnabled;
        }

        public String getBedrockModelId() {
            return bedrockModelId;
        }

        public void setBedrockModelId(String bedrockModelId) {
            this.bedrockModelId = bedrockModelId;
        }

        public int getBedrockMaxTokens() {
            return bedrockMaxTokens;
        }

        public void setBedrockMaxTokens(int bedrockMaxTokens) {
            this.bedrockMaxTokens = bedrockMaxTokens;
        }

        public double getBedrockTemperature() {
            return bedrockTemperature;
        }

        public void setBedrockTemperature(double bedrockTemperature) {
            this.bedrockTemperature = bedrockTemperature;
        }
    }
}
