package com.github.topi314.lavasrc.ytdlp;

import com.github.topi314.lavasrc.ExtendedAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.source.local.LocalSeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class YtdlpAudioTrack extends ExtendedAudioTrack {

	private static final Logger log = LoggerFactory.getLogger(YtdlpAudioTrack.class);

	private final YtdlpAudioSourceManager sourceManager;

	public YtdlpAudioTrack(AudioTrackInfo trackInfo, YtdlpAudioSourceManager sourceManager) {
		this(trackInfo, null, null, null, null, null, false, sourceManager);
	}

	public YtdlpAudioTrack(AudioTrackInfo trackInfo, String albumName, String albumUrl, String artistUrl, String artistArtworkUrl, String previewUrl, boolean isPreview, YtdlpAudioSourceManager sourceManager) {
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, previewUrl, isPreview);
		this.sourceManager = sourceManager;
	}

	private JsonBrowser getStreamUrl(String url) throws IOException {
		var args = new ArrayList<>(List.of(this.sourceManager.getCustomPlaybackArgs()));
		args.add(url);
		var process = this.sourceManager.getProcess(args);
		return this.sourceManager.getProcessJsonOutput(process);
	}

	@Override
	public void process(LocalAudioTrackExecutor executor) throws Exception {
		if (trackInfo.isStream) {
			var streamJson = this.getStreamUrl(trackInfo.uri);
			var streamUrl = new URI(streamJson.get("url").text());
			try (var httpInterface = this.sourceManager.getHttpInterface()) {
				if ("webm".equals(streamJson.get("ext").text())) {
					throw new FriendlyException("YouTube WebM streams are currently not supported.", FriendlyException.Severity.COMMON, null);
				}
				processDelegate(new YoutubeMpegStreamAudioTrack(trackInfo, httpInterface, streamUrl), executor);
			}
			return;
		}

		// Let yt-dlp handle the media request itself. Local playback does not
		// depend on reusing an extracted CDN URL with a different HTTP client.
		var directory = Files.createTempDirectory("lavasrc-ytdlp-");
		Process download = null;
		try {
			var args = new ArrayList<String>();
			args.add(this.sourceManager.getPath());
			// Preserve extractor/network options but remove the default JSON-only mode.
			for (var arg : this.sourceManager.getCustomPlaybackArgs()) {
				if (!List.of("-J", "--dump-single-json", "-j", "--dump-json", "-s", "--simulate",
					"--skip-download").contains(arg)) {
					args.add(arg);
				}
			}
			args.addAll(List.of("--no-simulate", "--no-playlist", "--no-progress", "--no-cache-dir",
				"--no-warnings", "-f", "bestaudio[ext=webm]/bestaudio[ext=m4a]",
				"--max-filesize", "100M", "--socket-timeout", "15",
				"-o", directory.resolve("audio.%(ext)s").toString(), "--", trackInfo.uri));

			download = new ProcessBuilder(args)
				.redirectErrorStream(true)
				.redirectOutput(directory.resolve("download.log").toFile())
				.start();

			if (!download.waitFor(180, TimeUnit.SECONDS)) {
				throw new FriendlyException("yt-dlp audio download timed out.", FriendlyException.Severity.COMMON, null);
			}
			if (download.exitValue() != 0) {
				throw new FriendlyException("yt-dlp audio download failed (exit " + download.exitValue() + ").", FriendlyException.Severity.COMMON, null);
			}

			Path audio = directory.resolve("audio.webm");
			boolean webm = Files.isRegularFile(audio);
			if (!webm) {
				audio = directory.resolve("audio.m4a");
			}
			if (!Files.isRegularFile(audio) || Files.size(audio) == 0 || Files.size(audio) > 100L * 1024 * 1024) {
				throw new FriendlyException("yt-dlp did not produce a supported audio file (limit 100 MiB).", FriendlyException.Severity.COMMON, null);
			}

			log.info("Playing yt-dlp temporary audio file ({} bytes)", Files.size(audio));
			try (var stream = new LocalSeekableInputStream(audio.toFile())) {
				if (webm) {
					processDelegate(new MatroskaAudioTrack(this.trackInfo, stream), executor);
				} else {
					processDelegate(new MpegAudioTrack(this.trackInfo, stream), executor);
				}
			}
		} finally {
			if (download != null && download.isAlive()) {
				download.descendants().forEach(ProcessHandle::destroyForcibly);
				download.destroyForcibly();
			}
			try (var files = Files.list(directory)) {
				for (var file : (Iterable<Path>) files::iterator) {
					Files.deleteIfExists(file);
				}
			} catch (IOException e) {
				log.warn("Could not clean up yt-dlp temporary files", e);
			}
			try {
				Files.deleteIfExists(directory);
			} catch (IOException e) {
				log.warn("Could not remove yt-dlp temporary directory", e);
			}
		}
	}

	@Override
	protected AudioTrack makeShallowClone() {
		return new YtdlpAudioTrack(this.trackInfo, this.albumName, this.albumUrl, this.artistUrl, this.artistArtworkUrl, this.previewUrl, this.isPreview, this.sourceManager);
	}

	@Override
	public AudioSourceManager getSourceManager() {
		return this.sourceManager;
	}

}
