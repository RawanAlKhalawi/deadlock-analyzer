package com.rawan.deadlock;

import com.rawan.deadlock.analyzer.DeadlockAnalyzer;
import com.rawan.deadlock.analyzer.DeadlockAnalyzer.DeadlockResult;
import com.rawan.deadlock.loader.TraceLoader;
import com.rawan.deadlock.model.Transaction;
import com.rawan.deadlock.report.DeadlockReport;
import com.rawan.deadlock.report.DeadlockReporter;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * Main CLI entry point for WeSEER deadlock analyzer.
 */
public class Main {
    
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    
    public static void main(String[] args) {
        // Define CLI options
        Options options = new Options();
        
        Option tracesOption = Option.builder()
            .longOpt("traces")
            .hasArg()
            .required()
            .desc("Input directory with JSON trace files")
            .build();
        
        Option outputOption = Option.builder()
            .longOpt("output")
            .hasArg()
            .required()
            .desc("Output directory for reports")
            .build();
        
        Option timeoutOption = Option.builder()
            .longOpt("timeout")
            .hasArg()
            .desc("Z3 solver timeout in seconds (default: 30)")
            .build();
        
        Option formatOption = Option.builder()
            .longOpt("format")
            .hasArg()
            .desc("Output format: json | html | both (default: both)")
            .build();
        
        Option verboseOption = Option.builder()
            .longOpt("verbose")
            .desc("Enable debug logging")
            .build();
        
        options.addOption(tracesOption);
        options.addOption(outputOption);
        options.addOption(timeoutOption);
        options.addOption(formatOption);
        options.addOption(verboseOption);
        
        // Parse command line arguments
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;
        
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println("Error: " + e.getMessage());
            formatter.printHelp("deadlock-analyzer", options);
            System.exit(1);
            return;
        }
        
        // Get command line values
        String tracesDir = cmd.getOptionValue("traces");
        String outputDir = cmd.getOptionValue("output");
        int timeout = Integer.parseInt(cmd.getOptionValue("timeout", "30"));
        String format = cmd.getOptionValue("format", "both");
        boolean verbose = cmd.hasOption("verbose");
        
        // Configure logging level
        if (verbose) {
            ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.DEBUG);
            logger.info("Debug logging enabled");
        }
        
        // Validate format option
        if (!format.equals("json") && !format.equals("html") && !format.equals("both")) {
            System.err.println("Error: Invalid format option. Must be 'json', 'html', or 'both'");
            System.exit(1);
            return;
        }
        
        // Validate directories
        File tracesDirFile = new File(tracesDir);
        if (!tracesDirFile.exists() || !tracesDirFile.isDirectory()) {
            System.err.println("Error: Traces directory does not exist or is not a directory: " + tracesDir);
            System.exit(1);
            return;
        }
        
        File outputDirFile = new File(outputDir);
        if (!outputDirFile.exists()) {
            boolean created = outputDirFile.mkdirs();
            if (!created) {
                System.err.println("Error: Failed to create output directory: " + outputDir);
                System.exit(1);
                return;
            }
        }
        
        if (!outputDirFile.isDirectory()) {
            System.err.println("Error: Output path is not a directory: " + outputDir);
            System.exit(1);
            return;
        }
        
        try {
            // Run deadlock analysis
            runAnalysis(tracesDir, outputDir, timeout, format);
        } catch (Exception e) {
            logger.error("Fatal error during analysis", e);
            System.err.println("Error: Analysis failed - " + e.getMessage());
            System.exit(1);
        }
    }
    
    /**
     * Main analysis workflow.
     */
    private static void runAnalysis(String tracesDir, String outputDir, int timeout, String format) throws IOException {
        logger.info("Starting WeSEER deadlock analysis");
        logger.info("Traces directory: {}", tracesDir);
        logger.info("Output directory: {}", outputDir);
        logger.info("Z3 timeout: {} seconds", timeout);
        logger.info("Output format: {}", format);
        
        // Step 1: Load traces
        System.out.println("Loading traces from: " + tracesDir);
        TraceLoader loader = new TraceLoader();
        List<Transaction> transactions = loader.loadFromDirectory(tracesDir);
        
        if (transactions.isEmpty()) {
            System.out.println("No transactions loaded. Exiting.");
            logger.warn("No transactions loaded from directory: {}", tracesDir);
            return;
        }
        
        System.out.println("Loaded " + transactions.size() + " transaction(s)");
        logger.info("Loaded {} transactions", transactions.size());
        
        // Step 2: Run deadlock analysis
        System.out.println("Running deadlock analysis...");
        DeadlockAnalyzer analyzer = null;
        DeadlockResult result;
        
        try {
            analyzer = new DeadlockAnalyzer(timeout);
            result = analyzer.analyzeDeadlocks(transactions);
        } finally {
            if (analyzer != null) {
                analyzer.close();
            }
        }
        
        // Step 3: Generate reports
        System.out.println("Generating reports...");
        DeadlockReporter reporter = new DeadlockReporter();
        List<DeadlockReport> reports = reporter.generateReports(result);
        
        int deadlockCount = reports.size();
        logger.info("Found {} deadlock(s)", deadlockCount);
        
        // Step 4: Save reports
        boolean jsonSaved = false;
        boolean htmlSaved = false;
        
        if (format.equals("json") || format.equals("both")) {
            String jsonOutput = reporter.toJson(reports);
            File jsonFile = new File(outputDir, "deadlock-report.json");
            try (FileWriter writer = new FileWriter(jsonFile)) {
                writer.write(jsonOutput);
                jsonSaved = true;
                logger.info("JSON report saved to: {}", jsonFile.getAbsolutePath());
            } catch (IOException e) {
                logger.error("Failed to save JSON report", e);
                System.err.println("Warning: Failed to save JSON report - " + e.getMessage());
            }
        }
        
        if (format.equals("html") || format.equals("both")) {
            String htmlOutput = reporter.toHtml(reports);
            File htmlFile = new File(outputDir, "deadlock-report.html");
            try (FileWriter writer = new FileWriter(htmlFile)) {
                writer.write(htmlOutput);
                htmlSaved = true;
                logger.info("HTML report saved to: {}", htmlFile.getAbsolutePath());
            } catch (IOException e) {
                logger.error("Failed to save HTML report", e);
                System.err.println("Warning: Failed to save HTML report - " + e.getMessage());
            }
        }
        
        // Step 5: Print summary
        System.out.println();
        System.out.println("========================================");
        System.out.println("Analysis complete. Found " + deadlockCount + " deadlock(s).");
        
        if (jsonSaved || htmlSaved) {
            System.out.println("Reports saved to: " + outputDir);
            if (jsonSaved) {
                System.out.println("  - deadlock-report.json");
            }
            if (htmlSaved) {
                System.out.println("  - deadlock-report.html");
            }
        }
        System.out.println("========================================");
        
        logger.info("Analysis complete");
    }
}
