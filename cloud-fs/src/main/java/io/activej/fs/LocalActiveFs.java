/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.fs;

import io.activej.async.service.EventloopService;
import io.activej.bytebuf.ByteBuf;
import io.activej.common.ApplicationSettings;
import io.activej.common.CollectorsEx;
import io.activej.common.MemSize;
import io.activej.common.collection.CollectionUtils;
import io.activej.common.exception.StacklessException;
import io.activej.common.exception.UncheckedException;
import io.activej.common.time.CurrentTimeProvider;
import io.activej.common.tuple.Tuple2;
import io.activej.csp.ChannelConsumer;
import io.activej.csp.ChannelSupplier;
import io.activej.csp.dsl.ChannelConsumerTransformer;
import io.activej.csp.file.ChannelFileReader;
import io.activej.csp.file.ChannelFileWriter;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.jmx.EventloopJmxBeanEx;
import io.activej.fs.LocalFileUtils.*;
import io.activej.fs.exception.*;
import io.activej.fs.exception.scalar.*;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.promise.Promise;
import io.activej.promise.Promise.BlockingCallable;
import io.activej.promise.Promise.BlockingRunnable;
import io.activej.promise.jmx.PromiseStats;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

import static io.activej.async.util.LogUtils.Level.TRACE;
import static io.activej.async.util.LogUtils.toLogger;
import static io.activej.common.Checks.checkArgument;
import static io.activej.common.collection.CollectionUtils.*;
import static io.activej.csp.dsl.ChannelConsumerTransformer.identity;
import static io.activej.fs.LocalFileUtils.*;
import static io.activej.fs.util.RemoteFsUtils.batchEx;
import static io.activej.fs.util.RemoteFsUtils.ofFixedSize;
import static java.nio.file.StandardOpenOption.*;
import static java.util.Collections.*;

/**
 * An implementation of {@link ActiveFs} which operates on a real underlying filesystem, no networking involved.
 * <p>
 * Only permits file operations to be made within a specified storage path.
 * <p>
 * This implementation does not define new limitations, other than those defined in {@link ActiveFs} interface.
 */
public final class LocalActiveFs implements ActiveFs, EventloopService, EventloopJmxBeanEx {
	private static final Logger logger = LoggerFactory.getLogger(LocalActiveFs.class);

	public static final String DEFAULT_TEMP_DIR = ".upload";
	public static final boolean DEFAULT_SYNCED = ApplicationSettings.getBoolean(LocalActiveFs.class, "synced", false);
	public static final boolean DEFAULT_SYNCED_APPEND = ApplicationSettings.getBoolean(LocalActiveFs.class, "syncedAppend", false);

	private static final char SEPARATOR_CHAR = SEPARATOR.charAt(0);
	private static final Function<String, String> toLocalName = File.separatorChar == SEPARATOR_CHAR ?
			Function.identity() :
			s -> s.replace(SEPARATOR_CHAR, File.separatorChar);

	private static final Function<String, String> toRemoteName = File.separatorChar == SEPARATOR_CHAR ?
			Function.identity() :
			s -> s.replace(File.separatorChar, SEPARATOR_CHAR);

	private final Eventloop eventloop;
	private final Path storage;
	private final Executor executor;

	private final Set<OpenOption> appendOptions = CollectionUtils.set(WRITE);
	private final Set<OpenOption> appendNewOptions = CollectionUtils.set(WRITE, CREATE);

	private MemSize readerBufferSize = MemSize.kilobytes(256);
	private boolean hardlinkOnCopy = false;
	private Path tempDir;
	private boolean synced = DEFAULT_SYNCED;

	CurrentTimeProvider now;

	//region JMX
	private final PromiseStats uploadBeginPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats uploadFinishPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats appendBeginPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats appendFinishPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats downloadBeginPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats downloadFinishPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats listPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats infoPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats infoAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats copyPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats copyAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats movePromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats moveAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats deletePromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats deleteAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	//endregion

	// region creators
	private LocalActiveFs(Eventloop eventloop, Path storage, Executor executor) {
		this.eventloop = eventloop;
		this.executor = executor;
		this.storage = storage;
		this.tempDir = storage.resolve(DEFAULT_TEMP_DIR);

		now = eventloop;

		if (DEFAULT_SYNCED_APPEND) {
			appendOptions.add(SYNC);
			appendNewOptions.add(SYNC);
		}
	}

	public static LocalActiveFs create(Eventloop eventloop, Executor executor, Path storageDir) {
		return new LocalActiveFs(eventloop, storageDir, executor);
	}

	/**
	 * Sets the buffer size for reading files from the filesystem.
	 */
	public LocalActiveFs withReaderBufferSize(MemSize size) {
		readerBufferSize = size;
		return this;
	}

	/**
	 * If set to {@code true}, an attempt to create a hard link will be made when copying files
	 */
	@SuppressWarnings("UnusedReturnValue")
	public LocalActiveFs withHardLinkOnCopy(boolean hardLinkOnCopy) {
		this.hardlinkOnCopy = hardLinkOnCopy;
		return this;
	}

	/**
	 * Sets a temporary directory for files to be stored while uploading.
	 */
	public LocalActiveFs withTempDir(Path tempDir) {
		this.tempDir = tempDir;
		return this;
	}

	/**
	 * If set to {@code true}, all newly created files (via move, copy, upload) will be synchronously persisted to the storage device.
	 * <p>
	 * <b>Note: may be slow when there are a lot of new files created</b>
	 */
	public LocalActiveFs withSynced(boolean synced) {
		this.synced = synced;
		return this;
	}

	/**
	 * If set to {@code true}, each write to {@link #append)} consumer will be synchronously written to the storage device.
	 * <p>
	 * <b>Note: significantly slows down appends</b>
	 */
	public LocalActiveFs withSyncedAppend(boolean syncedAppend) {
		if (syncedAppend) {
			appendOptions.add(SYNC);
			appendNewOptions.add(SYNC);
		} else {
			appendOptions.remove(SYNC);
			appendNewOptions.remove(SYNC);
		}
		return this;
	}
	// endregion

	@Override
	public Promise<ChannelConsumer<ByteBuf>> upload(@NotNull String name) {
		return uploadImpl(name, identity())
				.whenComplete(toLogger(logger, TRACE, "upload", name, this));
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> upload(@NotNull String name, long size) {
		return uploadImpl(name, ofFixedSize(size))
				.whenComplete(toLogger(logger, TRACE, "upload", name, size, this));
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> append(@NotNull String name, long offset) {
		checkArgument(offset >= 0, "Offset cannot be less than 0");
		return execute(
				() -> {
					Path path = resolve(name);
					FileChannel channel;
					if (offset == 0) {
						channel = ensureTarget(null, path, () -> FileChannel.open(path, appendNewOptions));
						if (synced) {
							tryFsync(path.getParent());
						}
					} else {
						channel = FileChannel.open(path, appendOptions);
					}
					long size = channel.size();
					if (size < offset) {
						throw new IllegalOffsetException(LocalActiveFs.class, "Offset " + offset + " exceeds file size " + size);
					}
					return channel;
				})
				.thenEx(translateScalarErrors(name))
				.whenComplete(appendBeginPromise.recordStats())
				.map(channel -> {
					ChannelFileWriter writer = ChannelFileWriter.create(executor, channel)
							.withOffset(offset);
					if (synced && !appendOptions.contains(SYNC)) {
						writer.withForceOnClose(true);
					}
					return writer
							.withAcknowledgement(ack -> ack
									.thenEx(translateScalarErrors(name))
									.whenComplete(appendFinishPromise.recordStats())
									.whenComplete(toLogger(logger, TRACE, "onAppendComplete", name, offset, this)));
				})
				.whenComplete(toLogger(logger, TRACE, "append", name, offset, this));
	}

	@Override
	public Promise<ChannelSupplier<ByteBuf>> download(@NotNull String name, long offset, long limit) {
		checkArgument(offset >= 0, "offset < 0");
		checkArgument(limit >= 0, "limit < 0");

		return resolveAsync(name)
				.then(path -> execute(() -> {
					FileChannel channel = FileChannel.open(path, READ);
					long size = channel.size();
					if (size < offset) {
						throw new IllegalOffsetException(LocalActiveFs.class, "Offset " + offset + " exceeds file size " + size);
					}
					return channel;
				}))
				.map(channel -> ChannelFileReader.create(executor, channel)
						.withBufferSize(readerBufferSize)
						.withOffset(offset)
						.withLimit(limit)
						.withEndOfStream(eos -> eos
								.thenEx(translateScalarErrors(name))
								.whenComplete(downloadFinishPromise.recordStats())
								.whenComplete(toLogger(logger, TRACE, "onDownloadComplete", name, offset, limit))))
				.thenEx(translateScalarErrors(name))
				.whenComplete(toLogger(logger, TRACE, "download", name, offset, limit, this))
				.whenComplete(downloadBeginPromise.recordStats());
	}

	@Override
	public Promise<Map<String, FileMetadata>> list(@NotNull String glob) {
		if (glob.isEmpty()) return Promise.of(emptyMap());

		return execute(
				() -> {
					String subdir = extractSubDir(glob);
					Path subdirectory = resolve(subdir);
					String subglob = glob.substring(subdir.length());

					return LocalFileUtils.findMatching(tempDir, subglob, subdirectory).stream()
							.collect(Collector.of(
									(Supplier<Map<String, FileMetadata>>) HashMap::new,
									(map, path) -> {
										FileMetadata metadata = toFileMetadata(path);
										if (metadata != null) {
											String filename = toRemoteName.apply(storage.relativize(path).toString());
											map.put(filename, metadata);
										}
									},
									CollectorsEx.throwingMerger())
							);
				})
				.thenEx(translateScalarErrors())
				.whenComplete(toLogger(logger, TRACE, "list", glob, this))
				.whenComplete(listPromise.recordStats());
	}

	@Override
	public Promise<Void> copy(@NotNull String name, @NotNull String target) {
		return execute(() -> forEachPair(singletonMap(name, target), this::doCopy))
				.thenEx(translateScalarErrors())
				.whenComplete(toLogger(logger, TRACE, "copy", name, target, this))
				.whenComplete(copyPromise.recordStats());
	}

	@Override
	public Promise<Void> copyAll(Map<String, String> sourceToTarget) {
		checkArgument(isBijection(sourceToTarget), "Targets must be unique");
		if (sourceToTarget.isEmpty()) return Promise.complete();

		return execute(() -> forEachPair(sourceToTarget, this::doCopy))
				.whenComplete(toLogger(logger, TRACE, "copyAll", toLimitedString(sourceToTarget, 50), this))
				.whenComplete(copyAllPromise.recordStats());
	}

	@Override
	public Promise<Void> move(@NotNull String name, @NotNull String target) {
		return execute(() -> forEachPair(singletonMap(name, target), this::doMove))
				.thenEx(translateScalarErrors())
				.whenComplete(toLogger(logger, TRACE, "move", name, target, this))
				.whenComplete(movePromise.recordStats());
	}

	@Override
	public Promise<Void> moveAll(Map<String, String> sourceToTarget) {
		checkArgument(isBijection(sourceToTarget), "Targets must be unique");
		if (sourceToTarget.isEmpty()) return Promise.complete();

		return execute(() -> forEachPair(sourceToTarget, this::doMove))
				.whenComplete(toLogger(logger, TRACE, "moveAll", toLimitedString(sourceToTarget, 50), this))
				.whenComplete(moveAllPromise.recordStats());
	}

	@Override
	public Promise<Void> delete(@NotNull String name) {
		return execute(() -> deleteImpl(singleton(name)))
				.thenEx(translateScalarErrors(name))
				.whenComplete(toLogger(logger, TRACE, "delete", name, this))
				.whenComplete(deletePromise.recordStats());
	}

	@Override
	public Promise<Void> deleteAll(Set<String> toDelete) {
		if (toDelete.isEmpty()) return Promise.complete();

		return execute(() -> deleteImpl(toDelete))
				.whenComplete(toLogger(logger, TRACE, "deleteAll", toDelete, this))
				.whenComplete(deleteAllPromise.recordStats());
	}

	@Override
	public Promise<Void> ping() {
		return Promise.complete(); // local fs is always available
	}

	@Override
	public Promise<@Nullable FileMetadata> info(@NotNull String name) {
		return execute(() -> toFileMetadata(resolve(name)))
				.whenComplete(toLogger(logger, TRACE, "info", name, this))
				.whenComplete(infoPromise.recordStats());
	}

	@Override
	public Promise<Map<String, @NotNull FileMetadata>> infoAll(@NotNull Set<String> names) {
		if (names.isEmpty()) return Promise.of(emptyMap());

		return execute(
				() -> {
					Map<String, FileMetadata> result = new HashMap<>();
					for (String name : names) {
						FileMetadata metadata = toFileMetadata(resolve(name));
						if (metadata != null) {
							result.put(name, metadata);
						}
					}
					return result;
				})
				.whenComplete(toLogger(logger, TRACE, "infoAll", names, this))
				.whenComplete(infoAllPromise.recordStats());
	}

	@NotNull
	@Override
	public Eventloop getEventloop() {
		return eventloop;
	}

	@NotNull
	@Override
	public Promise<Void> start() {
		return execute(() -> LocalFileUtils.init(storage, tempDir, synced));
	}

	@NotNull
	@Override
	public Promise<Void> stop() {
		return Promise.complete();
	}

	@Override
	public String toString() {
		return "LocalActiveFs{storage=" + storage + '}';
	}

	private IsADirectoryException dirEx(String name) {
		return new IsADirectoryException(LocalActiveFs.class, "Path '" + name + "' is a directory");
	}

	private Path resolve(String name) throws ForbiddenPathException {
		return LocalFileUtils.resolve(storage, tempDir, toLocalName.apply(name));
	}

	private Promise<Path> resolveAsync(String name) {
		try {
			return Promise.of(resolve(name));
		} catch (FsException e) {
			return Promise.ofException(e);
		}
	}

	private Promise<ChannelConsumer<ByteBuf>> uploadImpl(String name, ChannelConsumerTransformer<ByteBuf, ChannelConsumer<ByteBuf>> transformer) {
		return execute(
				() -> {
					Path tempPath = Files.createTempFile(tempDir, "upload", "");
					return new Tuple2<>(tempPath, FileChannel.open(tempPath, CREATE, WRITE));
				})
				.map(pathAndChannel -> {
					ChannelFileWriter writer = ChannelFileWriter.create(executor, pathAndChannel.getValue2());
					if (synced) {
						writer.withForceOnClose(true);
					}
					return writer
							.transformWith(transformer)
							.withAcknowledgement(ack -> ack
									.then(() -> execute(() -> {
										Path target = resolve(name);
										doMove(pathAndChannel.getValue1(), target);
										if (synced) {
											tryFsync(target.getParent());
										}
									}))
									.thenEx(translateScalarErrors(name))
									.whenException(() -> execute(() -> Files.deleteIfExists(pathAndChannel.getValue1())))
									.whenComplete(uploadFinishPromise.recordStats())
									.whenComplete(toLogger(logger, TRACE, "onUploadComplete", name, this)));
				})
				.thenEx(translateScalarErrors(name))
				.whenComplete(uploadBeginPromise.recordStats());
	}

	private void forEachPair(Map<String, String> sourceToTargetMap, IOScalarBiConsumer consumer) throws FsBatchException, FsIOException {
		Set<Path> toFSync = new HashSet<>();
		try {
			for (Map.Entry<String, String> entry : sourceToTargetMap.entrySet()) {
				translateBatchErrors(entry, () -> {
					Path path = resolve(entry.getKey());
					if (Files.readAttributes(path, BasicFileAttributes.class).isDirectory()) {
						throw new IsADirectoryException(LocalActiveFs.class, "File '" + entry.getKey() + "' is a directory");
					}
					Path targetPath = resolve(entry.getValue());
					if (path.equals(targetPath)) {
						touch(path, now);
						if (synced) {
							toFSync.add(path);
						}
						return;
					}

					consumer.accept(path, targetPath);
					if (synced) {
						toFSync.add(targetPath.getParent());
					}
				});
			}
		} finally {
			for (Path path : toFSync) {
				tryFsync(path);
			}
		}
	}

	private void doMove(Path path, Path targetPath) throws IOException, FsScalarException {
		ensureTarget(path, targetPath, () -> moveViaHardlink(path, targetPath, now));
	}

	private void doCopy(Path path, Path targetPath) throws IOException, FsScalarException {
		if (hardlinkOnCopy) {
			try {
				ensureTarget(path, targetPath, () -> copyViaHardlink(path, targetPath, now));
			} catch (IOException | FsScalarException e) {
				logger.warn("Could not copy via hard link, trying to copy via temporary directory", e);
				try {
					ensureTarget(path, targetPath, () -> copyViaTempDir(path, targetPath, now, tempDir));
				} catch (IOException e2) {
					e.addSuppressed(e2);
					throw e;
				}
			}
		} else {
			ensureTarget(path, targetPath, () -> copyViaTempDir(path, targetPath, now, tempDir));
		}
	}

	private void deleteImpl(Set<String> toDelete) throws FsBatchException, FsIOException {
		for (String name : toDelete) {
			translateBatchErrors(name, () -> {
				Path path = resolve(name);
				// cannot delete storage
				if (path.equals(storage)) return;

				try {
					Files.deleteIfExists(path);
				} catch (DirectoryNotEmptyException e) {
					throw dirEx(name);
				}
			});
		}
	}

	private <V> V ensureTarget(@Nullable Path source, Path target, IOCallable<V> afterCreation) throws IOException, FsScalarException {
		try {
			return LocalFileUtils.ensureTarget(source, target, synced, afterCreation);
		} catch (NoSuchFileException e) {
			throw e;
		} catch (DirectoryNotEmptyException e) {
			throw dirEx(storage.relativize(target).toString());
		} catch (FileSystemException e) {
			throw new PathContainsFileException(LocalActiveFs.class);
		}
	}

	private void ensureTarget(@Nullable Path source, Path target, IORunnable afterCreation) throws IOException, FsScalarException {
		ensureTarget(source, target, () -> {
			afterCreation.run();
			return null;
		});
	}

	@Nullable
	private FileMetadata toFileMetadata(Path path) {
		try {
			return LocalFileUtils.toFileMetadata(path);
		} catch (IOException e) {
			logger.warn("Failed to retrieve metadata for {}", path, e);
			throw new UncheckedException(new FsIOException(LocalActiveFs.class, "Failed to retrieve metadata"));
		}
	}

	private <T> Promise<T> execute(BlockingCallable<T> callable) {
		return Promise.ofBlockingCallable(executor, callable);
	}

	private Promise<Void> execute(BlockingRunnable runnable) {
		return Promise.ofBlockingRunnable(executor, runnable);
	}

	private <T> BiFunction<T, @Nullable Throwable, Promise<? extends T>> translateScalarErrors() {
		return translateScalarErrors(null);
	}

	private <T> BiFunction<T, @Nullable Throwable, Promise<? extends T>> translateScalarErrors(@Nullable String name) {
		return (v, e) -> {
			if (e == null) {
				return Promise.of(v);
			} else if (e instanceof FsBatchException) {
				Map<String, FsScalarException> exceptions = ((FsBatchException) e).getExceptions();
				assert exceptions.size() == 1;
				return Promise.ofException(first(exceptions.values()));
			} else if (e instanceof StacklessException) {
				return Promise.ofException(e);
			} else if (e instanceof FileAlreadyExistsException) {
				return execute(() -> {
					if (name != null && Files.isDirectory(resolve(name))) throw dirEx(name);
					throw new PathContainsFileException(LocalActiveFs.class);
				});
			} else if (e instanceof NoSuchFileException) {
				return Promise.ofException(new FileNotFoundException(LocalActiveFs.class));
			} else if (e instanceof GlobException) {
				return Promise.ofException(new MalformedGlobException(LocalActiveFs.class, e.getMessage()));
			}
			return execute(() -> {
				if (name != null) {
					Path path = resolve(name);
					if (!Files.exists(path))
						throw new FileNotFoundException(LocalActiveFs.class, "File '" + name + "' not found");
					if (Files.isDirectory(path)) throw dirEx(name);
				}
				logger.warn("Operation failed", e);
				if (e instanceof IOException) {
					throw new FsIOException(LocalActiveFs.class, "IO Error");
				}
				throw new FsIOException(LocalActiveFs.class, "Unknown error");
			});
		};
	}

	private void translateBatchErrors(Map.Entry<String, String> entry, IOScalarRunnable runnable) throws FsBatchException, FsIOException {
		String first = entry.getKey();
		String second = entry.getValue();
		try {
			runnable.run();
		} catch (FsScalarException e) {
			throw batchEx(LocalActiveFs.class, first, e);
		} catch (FileAlreadyExistsException e) {
			checkIfDirectories(first, second);
			throw batchEx(LocalActiveFs.class, first, new PathContainsFileException(LocalActiveFs.class));
		} catch (NoSuchFileException e) {
			throw batchEx(LocalActiveFs.class, first, new FileNotFoundException(LocalActiveFs.class));
		} catch (IOException e) {
			checkIfExists(first);
			checkIfDirectories(first, second);
			logger.warn("Operation failed", e);
			throw new FsIOException(LocalActiveFs.class, "IO Error");
		} catch (Exception e) {
			logger.warn("Operation failed", e);
			throw new FsIOException(LocalActiveFs.class, "Unknown Error");
		}
	}

	private void translateBatchErrors(@NotNull String first, IOScalarRunnable runnable) throws FsBatchException, FsIOException {
		translateBatchErrors(new AbstractMap.SimpleEntry<>(first, null), runnable);
	}

	private void checkIfDirectories(@NotNull String first, @Nullable String second) throws FsBatchException {
		try {
			if (Files.isDirectory(resolve(first))) {
				throw batchEx(LocalActiveFs.class, first, dirEx(first));
			}
		} catch (ForbiddenPathException e) {
			throw batchEx(LocalActiveFs.class, first, e);
		}
		try {
			if (Files.isDirectory(resolve(second))) {
				throw batchEx(LocalActiveFs.class, first, dirEx(second));
			}
		} catch (ForbiddenPathException e) {
			throw batchEx(LocalActiveFs.class, first, e);
		}
	}

	private void checkIfExists(@NotNull String file) throws FsBatchException {
		try {
			if (!Files.exists(resolve(file))) {
				throw batchEx(LocalActiveFs.class, file, new FileNotFoundException(LocalActiveFs.class, "File '" + file + "' not found"));
			}
		} catch (ForbiddenPathException e) {
			throw batchEx(LocalActiveFs.class, file, e);
		}
	}

	@FunctionalInterface
	private interface IOScalarRunnable {
		void run() throws IOException, FsScalarException;
	}

	@FunctionalInterface
	private interface IOScalarBiConsumer {
		void accept(Path first, Path second) throws IOException, FsScalarException;
	}

	//region JMX
	@JmxAttribute
	public PromiseStats getUploadBeginPromise() {
		return uploadBeginPromise;
	}

	@JmxAttribute
	public PromiseStats getUploadFinishPromise() {
		return uploadFinishPromise;
	}

	@JmxAttribute
	public PromiseStats getAppendBeginPromise() {
		return uploadBeginPromise;
	}

	@JmxAttribute
	public PromiseStats getAppendFinishPromise() {
		return uploadFinishPromise;
	}

	@JmxAttribute
	public PromiseStats getDownloadBeginPromise() {
		return downloadBeginPromise;
	}

	@JmxAttribute
	public PromiseStats getDownloadFinishPromise() {
		return downloadFinishPromise;
	}

	@JmxAttribute
	public PromiseStats getListPromise() {
		return listPromise;
	}

	@JmxAttribute
	public PromiseStats getInfoPromise() {
		return infoPromise;
	}

	@JmxAttribute
	public PromiseStats getInfoAllPromise() {
		return infoAllPromise;
	}

	@JmxAttribute
	public PromiseStats getCopyPromise() {
		return copyPromise;
	}

	@JmxAttribute
	public PromiseStats getCopyAllPromise() {
		return copyAllPromise;
	}

	@JmxAttribute
	public PromiseStats getMovePromise() {
		return movePromise;
	}

	@JmxAttribute
	public PromiseStats getMoveAllPromise() {
		return moveAllPromise;
	}

	@JmxAttribute
	public PromiseStats getDeletePromise() {
		return deletePromise;
	}

	@JmxAttribute
	public PromiseStats getDeleteAllPromise() {
		return deleteAllPromise;
	}
	//endregion
}
