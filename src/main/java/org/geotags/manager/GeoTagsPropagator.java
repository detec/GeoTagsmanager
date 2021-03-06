package org.geotags.manager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.AbstractMap.SimpleEntry;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.ImageWriteException;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;

import com.drew.imaging.FileType;
import com.drew.imaging.FileTypeDetector;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;

public class GeoTagsPropagator {

	private static Logger LOG = Logger.getLogger("GeoTagsPropagator");
    private static final LinkOption NO_FOLLOW_LINKS = LinkOption.NOFOLLOW_LINKS;

    private static String pathString;
	private static Path startDirPath;

	private static Map<Path, BasicFileAttributeView> pathBasicFileAttributeViewMap = new LinkedHashMap<>();

	private static List<GeoTaggedPhotoWrapper> geotaggedPathsList = new ArrayList<>();
	// we process local date time, not path
	private static List<UntaggedPhotoWrapper> untaggedPathsList = new ArrayList<>();

	private static double rounding = 10000d;
	private static int assignedGPSCounter;
	private static int reassignedFileDates;

	public static void main(String[] args) {
		if (args.length != 1) {
			LOG.log(Level.INFO,
					"Invalid number of arguments. You should only specify path to directory with geotagged and not-geotagged photos.");
			System.exit(0);
		}
		pathString = args[0];
		validatePath();

		LOG.log(Level.INFO, "Argument checks passed, starting to process files in " + pathString);
		try {
			processFiles();
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "Error occurred when traversing directory " + pathString, e);
		}

		LOG.log(Level.INFO, "Processed untagged image files with geotags: " + assignedGPSCounter);
		LOG.log(Level.INFO, "Reassigned dates for files: " + reassignedFileDates);
		LOG.log(Level.INFO, "Finished processing for path " + pathString);

	}

	private static void validatePath() {

		startDirPath = Paths.get(pathString);

		// check existence
        boolean isExistingPath = Files.exists(startDirPath, NO_FOLLOW_LINKS);
		if (!isExistingPath) {
			throw new IllegalArgumentException("Path does not exist or is not accessible: " + pathString);
		}

		// check if it is a directory
        boolean isDirectory = Files.isDirectory(startDirPath, NO_FOLLOW_LINKS);
		if (!isDirectory) {
			throw new IllegalArgumentException("Path is not a directory: " + pathString);
		}

		// check read/write capabilities.
		boolean isReadble = Files.isReadable(startDirPath);
		if (!isReadble) {
			throw new IllegalArgumentException("Path is not readable: " + pathString);
		}

		boolean isWritable = Files.isWritable(startDirPath);
		if (!isWritable) {
			throw new IllegalArgumentException("Path is not writable: " + pathString);
		}
	}


	private static void processFiles() throws IOException {

		try (Stream<Path> nestedFilesStreamPath = Files.walk(startDirPath);) {
			nestedFilesStreamPath.forEach(GeoTagsPropagator::fillPathLists);
		}

		boolean needReturn = false;

		if (geotaggedPathsList.isEmpty()) {
			needReturn = true;
		}

		if (untaggedPathsList.isEmpty()) {
			LOG.log(Level.INFO, "No untagged photos found at " + pathString);
			needReturn = true;
		}

		LOG.log(Level.INFO,
				"Geotagged files found: " + geotaggedPathsList.size() + ", files to tag: " + untaggedPathsList.size());

		if (needReturn) {
			// before quitting - we process dates.
            untaggedPathsList.forEach(GeoTagsPropagator::assignFileDate);
			return;
		}

        untaggedPathsList.forEach(GeoTagsPropagator::tagUntaggedPath);
        untaggedPathsList.forEach(GeoTagsPropagator::assignFileDate);
	}

    private static void tagUntaggedPath(UntaggedPhotoWrapper untaggedWrapper) {
        Function<Instant, Long> minutesDiffFunction = taggedInstant -> Math
                .abs(taggedInstant.until(untaggedWrapper.getFileDateTime(), ChronoUnit.MINUTES));

        // difference must be not less than 1 hour, this is the time to change
        // location
        geotaggedPathsList.stream().filter(geoTagged -> minutesDiffFunction.apply(geoTagged.getFileDateTime()) <= 60)
                .map(geoTagged -> new SimpleEntry<GeoLocation, Long>(geoTagged.getGeoLocation(),
                        minutesDiffFunction.apply(geoTagged.getFileDateTime())))
                .sorted((e1, e2) -> Long.compare(e1.getValue(), e2.getValue())).findFirst()
                .ifPresent(entry -> assignGeoLocation(entry.getKey(), untaggedWrapper));
	}

    private static void assignFileDate(UntaggedPhotoWrapper untaggedWrapper) {
        var unTaggedPath = untaggedWrapper.getPath();
        // here we assign file attributes.
        Optional.ofNullable(pathBasicFileAttributeViewMap.get(unTaggedPath))
                .ifPresent(bfaView -> assignCommonFileTime(bfaView,
                        getFileTimeFromInstant(untaggedWrapper.getFileDateTime()), unTaggedPath));
    }

	private static void assignGeoLocation(GeoLocation geoLocation, UntaggedPhotoWrapper untaggedWrapper) {

		TiffOutputSet outputSet = null;
        var path = untaggedWrapper.getPath();
        var imageFile = path.toFile();

		try {
            var jpegMetadata = (JpegImageMetadata) Imaging.getMetadata(imageFile);
			if (jpegMetadata != null) {
				// note that exif might be null if no Exif metadata is found.
                var exif = jpegMetadata.getExif();
				if (null != exif) {
					outputSet = getTiffOutputSet(exif, path);
				} else {
					outputSet = new TiffOutputSet();
				}
				outputSet.setGPSInDegrees(geoLocation.getLongitude(), geoLocation.getLatitude());
			}
        } catch (ImageReadException | IOException | ImageWriteException e) {
			LOG.log(Level.WARNING, "Could not get/set image metadata from " + path.toString(), e);
			return;
		}

        var formatTmp = "%stmp";
        var outFile = new File(String.format(formatTmp, path.toString()));

        try (var os = new BufferedOutputStream(new FileOutputStream(outFile));) {
			new ExifRewriter().updateExifMetadataLossless(imageFile, os, outputSet);
        } catch (ImageReadException | ImageWriteException e) {
			LOG.log(Level.WARNING, "Error update exif metadata " + outFile.getAbsolutePath(), e);
		} catch (IOException e) {
			LOG.log(Level.WARNING, "Error I/O file " + outFile.getAbsolutePath(), e);
		}

		// renaming from temp file
		try {
			Files.move(outFile.toPath(), path, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LOG.log(Level.WARNING, "Could not move image file " + outFile.getAbsolutePath(), e);
		}
		assignedGPSCounter++;
	}

	private static TiffOutputSet getTiffOutputSet(TiffImageMetadata exif, Path path) {
		// TiffImageMetadata class is immutable (read-only).
		// TiffOutputSet class represents the Exif data to write.
		//
		// Usually, we want to update existing Exif metadata by
		// changing
		// the values of a few fields, or adding a field.
		// In these cases, it is easiest to use getOutputSet() to
		// start with a "copy" of the fields read from the image.
		try {
            return exif.getOutputSet();
		} catch (ImageWriteException e) {
			LOG.log(Level.WARNING, "Could not get EXIF output set from " + path.toString(), e);
			return null;
		}
	}

	private static void fillPathLists(Path path) {
		// omitting directories
        var isDirectory = Files.isDirectory(path, NO_FOLLOW_LINKS);
        if (isDirectory)
            return;

        var file = path.toFile();
        try (var inputStream = new FileInputStream(file);) {
            var bufferedInputStream = new BufferedInputStream(inputStream);
            var fileType = FileTypeDetector.detectFileType(bufferedInputStream);

			if (fileType != FileType.Jpeg) {
				// we do not process files other than Jpeg.
				return;
			}
		} catch (IOException e) {
			LOG.log(Level.WARNING, "Could not read file " + path.toString(), e);
		}

        Metadata metadata = null;
		try {
			metadata = ImageMetadataReader.readMetadata(file);
		} catch (ImageProcessingException | IOException e) {
			LOG.log(Level.WARNING, "Error reading metadata from " + path.toString(), e);
			return;
		}

        var gpsDirectories = metadata.getDirectoriesOfType(GpsDirectory.class);
		if (gpsDirectories.isEmpty()) {
			processUntaggedFile(path, metadata);
			// it is a usual file without geotags
		} else {
			processGeoTaggedFile(path, gpsDirectories, metadata);
		}
	}

    private static Instant getExifLDTFromMetadataExtractorMetadata(Metadata metadata) {
		// from https://github.com/drewnoakes/metadata-extractor/wiki/FAQ
        var exifDirectories = metadata.getDirectoriesOfType(ExifSubIFDDirectory.class);
		if (exifDirectories.isEmpty()) {
            return null;
		}

        var exifDir = exifDirectories.iterator().next();
        var exifDate = exifDir.getDate(ExifDirectoryBase.TAG_DATETIME_ORIGINAL);
        return convertDateToInstant(exifDate);
	}

	private static void processUntaggedFile(Path path, Metadata metadata) {

        var exifInstant = getExifLDTFromMetadataExtractorMetadata(metadata);
        if (exifInstant == null) {
			LOG.log(Level.WARNING, "No ExifSubIFDDirectory for " + path.toString());
			return;
		}

        var untaggedWrapper = new UntaggedPhotoWrapper(path, exifInstant, metadata);
		untaggedPathsList.add(untaggedWrapper);
		pathBasicFileAttributeViewMap.put(path, Files.getFileAttributeView(path, BasicFileAttributeView.class));
	}

	private static void processGeoTaggedFile(Path path, Collection<GpsDirectory> gpsDirectories, Metadata metadata) {

        var exifInstant = getExifLDTFromMetadataExtractorMetadata(metadata);
        if (exifInstant == null) {
			LOG.log(Level.WARNING, "No ExifSubIFDDirectory for " + path.toString());

		}

        var gpsDir = gpsDirectories.iterator().next();
        var extractedGeoLocation = gpsDir.getGeoLocation();

        if (!(Objects.nonNull(extractedGeoLocation) && !extractedGeoLocation.isZero())) {
			return;
		}

        var correctedInstant = getCorrectedInstant(gpsDir, exifInstant);
        var roundedGeoLocation = getRoundedGeoLocation(extractedGeoLocation);

        var geoWrapper = new GeoTaggedPhotoWrapper(path, correctedInstant, roundedGeoLocation,
                gpsDir);
		geotaggedPathsList.add(geoWrapper);

        var pathBFAView = Files.getFileAttributeView(path, BasicFileAttributeView.class);
        var universalFT = getFileTimeFromInstant(correctedInstant);
		assignCommonFileTime(pathBFAView, universalFT, path);
	}

	private static GeoLocation getRoundedGeoLocation(GeoLocation extractedGeoLocation) {

		// here we should process geolocation and round it somehow up to 10-20
		// meters.
        var unRoundedLatitude = extractedGeoLocation.getLatitude();
        var roundedLatitude = roundTo4DecimalPlaces(unRoundedLatitude);

        var unRoundedLongitude = extractedGeoLocation.getLongitude();
        var roundedLongitude = roundTo4DecimalPlaces(unRoundedLongitude);

        // constructing rounded geolocation for path.
		return new GeoLocation(roundedLatitude, roundedLongitude);
	}

    private static Instant getCorrectedInstant(GpsDirectory gpsDir, Instant exifInstant) {
        Instant correctedInstant;
        var gpsDate = gpsDir.getGpsDate();

        if (Objects.isNull(exifInstant) && Objects.isNull(gpsDate)) {
            return null; // it is null
        }

        if (gpsDate != null) {
            var gpsInstant = convertDateToInstant(gpsDate);
            if (exifInstant == null) {
                return gpsInstant;
            }

            var minutesDiff = gpsInstant.until(exifInstant, ChronoUnit.MINUTES);
            correctedInstant = (minutesDiff % 60 == 0) ? exifInstant : gpsInstant;
        } else {
            // gps date is null
            correctedInstant = exifInstant;
        }
        return correctedInstant;
	}

	private static void assignCommonFileTime(BasicFileAttributeView pathBFAView, FileTime universalFT, Path path) {
		try {
			pathBFAView.setTimes(universalFT, universalFT, universalFT);
			reassignedFileDates++;
		} catch (IOException e) {
			LOG.log(Level.WARNING, "Could not set attributes for file: " + path.toString(), e);
		}
	}

    private static FileTime getFileTimeFromInstant(Instant instant) {
        return Objects.isNull(instant) ? null : FileTime.from(instant);
	}

    private static Instant convertDateToInstant(Date date) {
        return Objects.isNull(date) ? null : date.toInstant();
    }

	private static double roundTo4DecimalPlaces(double value) {
		return Math.round(value * rounding) / rounding;
	}
}
