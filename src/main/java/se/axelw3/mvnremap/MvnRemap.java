package se.axelw3.mvnremap;

import java.io.IOException;
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
import org.apache.maven.project.MavenProject;

import net.fabricmc.tinyremapper.ConsoleLogger;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyRemapper.LinkedMethodPropagation;
import net.fabricmc.tinyremapper.TinyUtils;

@Mojo(name = "remap", defaultPhase = LifecyclePhase.COMPILE)
public class MvnRemap extends AbstractMojo{

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "inputFile", required = true)
    private String inputFile;

    @Parameter(property = "outputFile", required = true)
    private String outputFile;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        System.out.println("Detta fungerar (" + project.getArtifactId() + ")!!!");

        // 1) remappa obfuskerad --> Fabric "intermediary mappings" (class_xxxx, etc.)
        final String mappingsFile = "1.21.1.tiny";
        final String fromNamespace = "official";
        final String toNamespace = "intermediary";

        ConsoleLogger logger = new ConsoleLogger();

        final Path input = Paths.get(inputFile);
        if (!Files.isReadable(input)) {
            logger.error("Couldn't read " + input + ".");
            System.exit(1);
        }

        Path output = Paths.get(outputFile);
        Path mappings = Paths.get(mappingsFile);
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
}