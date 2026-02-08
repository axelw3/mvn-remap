package se.axelw3.mvnremap;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.zip.ZipFile;

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

    @Parameter(property = "inputArchive", required = false, defaultValue = "")
    private String inputArchive;

    @Parameter(property = "inputFile", required = true)
    private String inputFile;

    @Parameter(property = "outputFile", required = true)
    private String outputFile;

    @Parameter(property = "mappingsFile", required = true)
    private String mappingsFile;

    @Parameter(property = "fromNamespace", required = true, defaultValue = "official")
    private String fromNamespace;

    @Parameter(property = "toNamespace", required = true, defaultValue = "intermediary")
    private String toNamespace;

    private final static String NAME_INPUT = "input.jar";
    private final static String NAME_TMP_ARCHIVE = "archive.tmp";

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        ConsoleLogger logger = new ConsoleLogger();

        Path output = Paths.get(outputFile);
        if(output.toFile().exists()){
            logger.info("Skipping remap (target file already exists).");
            return;
        }

        final Path input;
        if(inputArchive != null && !inputArchive.isEmpty()){
            input = Paths.get(NAME_INPUT);
            final Path srcPath = fetchIfAppropriate(inputArchive, NAME_TMP_ARCHIVE, logger);
            try(
                final ZipFile zipFile = new ZipFile(srcPath.toFile());
                final InputStream in = zipFile.getInputStream(zipFile.getEntry(inputFile));
                final FileOutputStream out = new FileOutputStream(input.toFile());
            ){
                out.getChannel().transferFrom(new ReadableByteChannel(){
                    @Override
                    public void close() throws IOException{ in.close(); }

                    @Override
                    public boolean isOpen(){ return in != null; }

                    @Override
                    public int read(ByteBuffer dst) throws IOException{
                        int beg = dst.position(),
                            rem = dst.remaining();

                        if(dst.hasArray()){
                            int rd = in.read(dst.array(), beg + dst.arrayOffset(), rem);
                            if(rd > 0) dst.position(beg + rd);
                            return rd;
                        }

                        byte[] arr = new byte[rem];
                        int rd = in.read(arr, 0, rem);
                        if(rd > 0){
                            dst.put(arr, 0, rd);
                        }

                        return rd;
                    }
                }, 0, Long.MAX_VALUE);
            }catch(IOException e){
                logger.error("Couldn't open input archive.");
                System.exit(1);
            }
        }else input = fetchIfAppropriate(inputFile, NAME_INPUT, logger);

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
    private static Path fetchIfAppropriate(String srcPath, String nameOverride, ConsoleLogger logger){
        if(!srcPath.startsWith("http://") && !srcPath.startsWith("https://")) return Paths.get(srcPath);
        try{
            final URL url = new URL(srcPath);
            final Path p = Paths.get(nameOverride == null   ? url.getPath()
                                                            : nameOverride);
            try(
                final FileOutputStream fos = new FileOutputStream(p.toFile(), false);
                final ReadableByteChannel rbc = java.nio.channels.Channels.newChannel(url.openStream());
            ){
                fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            }catch(IOException err){
                logger.error("Couldn't fetch file from url.");
                System.exit(1);
                return null;
            }

            return p;
        }catch(MalformedURLException err){
            logger.error("Malformed file url.");
            System.exit(1);
        }

        return null;
    }
}