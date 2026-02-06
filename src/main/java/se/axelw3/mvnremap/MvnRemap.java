package se.axelw3.mvnremap;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import net.fabricmc.tinyremapper.ConsoleLogger;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyRemapper.LinkedMethodPropagation;
import net.fabricmc.tinyremapper.TinyUtils;

@Mojo(name = "remap", defaultPhase = LifecyclePhase.VALIDATE)
public class MvnRemap extends AbstractMojo{
/*
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
*/

    @Parameter(property = "inputFile", required = true)
    private String inputFile;

    @Parameter(property = "outputFile", required = true)
    private String outputFile;

    @Parameter(property = "mappingsFile", required = true)
    private String mappingsFile;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // 1) remappa obfuskerad --> Fabric "intermediary mappings" (class_xxxx, etc.)
        final String fromNamespace = "official";
        final String toNamespace = "intermediary";

        ConsoleLogger logger = new ConsoleLogger();

        Path output = Paths.get(outputFile);
        if(output.toFile().exists()){
            logger.info("Skipping remap (target file already exists).");
            return;
        }

        final Path input = fetchIfAppropriate(inputFile, "input.jar", logger);
        if (!Files.isReadable(input)) {
            logger.error("Couldn't read " + input + ".");
            System.exit(1);
        }

        Path mappings = fetchIfAppropriate(mappingsFile, "mappings.tiny", logger);
        if (!Files.isReadable(mappings) || Files.isDirectory(mappings, new LinkOption[0])) {
            logger.error("Couldn't read mappings " + mappings + ".");
            System.exit(1);
        }

        long startTime = System.nanoTime();
        TinyRemapper.Builder builder = TinyRemapper.newRemapper(logger)
            .withMappings(TinyUtils.createTinyMappingProvider(mappings, fromNamespace, toNamespace))
            .ignoreFieldDesc(false)
            .withForcedPropagation(Collections.emptySet())
            .withKnownIndyBsm(new HashSet<>())
            .propagatePrivate(false)
            .propagateBridges(LinkedMethodPropagation.DISABLED)
            .removeFrames(false)
            .ignoreConflicts(false)
            .checkPackageAccess(false)
            .fixPackageAccess(false)
            .resolveMissing(false)
            .rebuildSourceFilenames(false)
            .skipLocalVariableMapping(false)
            .renameInvalidLocals(false)
            .invalidLvNamePattern(null)
            .inferNameFromSameLvIndex(false)
            .threads(-1);

        TinyRemapper remapper = builder.build();

        try(OutputConsumerPath outputConsumer = (new OutputConsumerPath.Builder(output)).build()){
            outputConsumer.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, remapper);
            remapper.readInputs(new Path[]{input});
            remapper.apply(outputConsumer);
        }catch(IOException e){
            throw new RuntimeException(e);
        }finally{
            remapper.finish();
        }

        logger.info("Remapped jar archive in %.2fms.", (double) (System.nanoTime() - startTime) / 1e6d);
    }

    @SuppressWarnings("ConvertToTryWithResources")
    private static Path fetchIfAppropriate(String srcPath, String targetFileName, ConsoleLogger logger){
        if(srcPath.startsWith("http://") || srcPath.startsWith("https://")){
            final Path targetFile = Paths.get(targetFileName);
            try(FileOutputStream fos = new FileOutputStream(targetFile.toFile())){
                URL url = new URL(srcPath);
                ReadableByteChannel rbc = java.nio.channels.Channels.newChannel(url.openStream());
                fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                rbc.close();
            }catch(IOException err){
                logger.error("Couldn't fetch file from url.");
                System.exit(1);
            }

            return targetFile;
        }

        return Paths.get(srcPath);
    }
}