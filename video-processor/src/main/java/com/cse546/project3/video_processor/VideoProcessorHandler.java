package com.cse546.project3.video_processor;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@SpringBootApplication
public class VideoProcessorHandler implements RequestHandler<S3Event, String> {

	private final S3Client s3Client;
	private static final String TEMP_DIR = "/tmp/";
	private static final int LAMBDA_TIMEOUT = 900; // 15 minutes in seconds

	public VideoProcessorHandler() {
		this.s3Client = S3Client.builder()
				.region(Region.US_EAST_1)
				.build();
	}

	public static void main(String[] args) {
		SpringApplication.run(VideoProcessorHandler.class, args);
	}

	@Override
	public String handleRequest(S3Event s3Event, Context context) {
		try {
			// Get the S3 bucket and key
			String sourceBucket = s3Event.getRecords().get(0).getS3().getBucket().getName();
			String sourceKey = URLDecoder.decode(s3Event.getRecords().get(0).getS3().getObject().getKey(),
					StandardCharsets.UTF_8);
			String targetBucket = sourceBucket.replace("-input", "-stage-1");

			context.getLogger().log("Processing video from bucket: " + sourceBucket + ", key: " + sourceKey);

			// Validate file extension
			if (!sourceKey.toLowerCase().endsWith(".mp4")) {
				context.getLogger().log("Skipping non-MP4 file: " + sourceKey);
				return "Skipped non-MP4 file";
			}

			// Get video name without extension
			String videoName = sourceKey.substring(0, sourceKey.lastIndexOf('.'));

			// Create temporary directories
			String inputPath = TEMP_DIR + sourceKey;
			String outputDir = TEMP_DIR + videoName + "/";
			createDirectory(outputDir);

			// Download video from S3
			downloadFromS3(sourceBucket, sourceKey, inputPath);
			context.getLogger().log("Video downloaded successfully");

			// Process video
			processVideo(inputPath, outputDir);
			context.getLogger().log("Video processed successfully");

			// Upload processed frames to S3
			uploadFramesToS3(outputDir, targetBucket, videoName);
			context.getLogger().log("Frames uploaded successfully");

			// Cleanup temporary files
			cleanup(inputPath, outputDir);

			return "Video processing completed successfully for " + sourceKey;
		} catch (Exception e) {
			context.getLogger().log("Error processing video: " + e.getMessage());
			e.printStackTrace();
			throw new RuntimeException("Failed to process video: " + e.getMessage(), e);
		}
	}

	private void createDirectory(String directory) throws IOException {
		Path path = Paths.get(directory);
		if (!Files.exists(path)) {
			Files.createDirectories(path);
		}
	}

	private void downloadFromS3(String bucket, String key, String localPath) throws IOException {
		GetObjectRequest getObjectRequest = GetObjectRequest.builder()
				.bucket(bucket)
				.key(key)
				.build();

		Path downloadPath = Path.of(localPath);
		s3Client.getObject(getObjectRequest, downloadPath);
	}

	private void processVideo(String inputPath, String outputDir) throws IOException, InterruptedException {
		String[] command = {
				"ffmpeg",
				"-ss", "0",
				"-r", "1",
				"-i", inputPath,
				"-vf", "fps=1/10",
				"-start_number", "0",
				"-vframes", "10",
				outputDir + "output-%02d.jpg",
				"-y"
		};

		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.redirectErrorStream(true); // Merge stderr with stdout
		Process process = processBuilder.start();

		// Read both stdout and stderr
		String output = new String(process.getInputStream().readAllBytes());
		int exitCode = process.waitFor();

		if (exitCode != 0) {
			throw new RuntimeException("FFmpeg process failed with exit code: " + exitCode + "\nOutput: " + output);
		}
	}

	private void uploadFramesToS3(String outputDir, String targetBucket, String videoName) throws IOException {
		try (var files = Files.list(Path.of(outputDir))) {
			files.filter(path -> path.toString().endsWith(".jpg"))
					.forEach(frame -> {
						String s3Key = videoName + "/" + frame.getFileName().toString();
						PutObjectRequest putObjectRequest = PutObjectRequest.builder()
								.bucket(targetBucket)
								.key(s3Key)
								.build();

						s3Client.putObject(putObjectRequest, RequestBody.fromFile(frame));
					});
		}
	}

	private void cleanup(String inputPath, String outputDir) throws IOException {
		try {
			// Delete input video file
			Files.deleteIfExists(Path.of(inputPath));

			// Delete output directory and its contents
			try (var files = Files.walk(Path.of(outputDir))) {
				files.sorted((a, b) -> b.toString().compareTo(a.toString()))
						.forEach(path -> {
							try {
								Files.delete(path);
							} catch (IOException e) {
								System.err.println("Failed to delete: " + path);
							}
						});
			}
		} catch (Exception e) {
			// Log but don't throw as cleanup is not critical
			System.err.println("Cleanup failed: " + e.getMessage());
		}
	}
}