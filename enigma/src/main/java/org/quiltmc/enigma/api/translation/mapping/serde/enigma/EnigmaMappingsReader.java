package org.quiltmc.enigma.api.translation.mapping.serde.enigma;

import org.quiltmc.enigma.api.ProgressListener;
import org.quiltmc.enigma.api.translation.mapping.EntryMapping;
import org.quiltmc.enigma.api.translation.mapping.MappingPair;
import org.quiltmc.enigma.impl.translation.mapping.serde.MappingHelper;
import org.quiltmc.enigma.api.translation.mapping.serde.MappingParseException;
import org.quiltmc.enigma.api.translation.mapping.serde.MappingsReader;
import org.quiltmc.enigma.impl.translation.mapping.serde.RawEntryMapping;
import org.quiltmc.enigma.api.translation.mapping.tree.EntryTree;
import org.quiltmc.enigma.api.translation.mapping.tree.HashEntryTree;
import org.quiltmc.enigma.api.translation.representation.MethodDescriptor;
import org.quiltmc.enigma.api.translation.representation.TypeDescriptor;
import org.quiltmc.enigma.api.translation.representation.entry.ClassEntry;
import org.quiltmc.enigma.api.translation.representation.entry.Entry;
import org.quiltmc.enigma.api.translation.representation.entry.FieldEntry;
import org.quiltmc.enigma.api.translation.representation.entry.LocalVariableEntry;
import org.quiltmc.enigma.api.translation.representation.entry.MethodEntry;
import org.quiltmc.enigma.util.I18n;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public enum EnigmaMappingsReader implements MappingsReader {
	FILE {
		@Override
		public EntryTree<EntryMapping> read(Path path, ProgressListener progress) throws IOException, MappingParseException {
			progress.init(1, I18n.translate("progress.mappings.enigma_file.loading"));

			EntryTree<EntryMapping> mappings = new HashEntryTree<>();
			readFile(path, mappings);

			progress.step(1, I18n.translate("progress.mappings.enigma_file.done"));

			return mappings;
		}
	},
	DIRECTORY {
		@Override
		public EntryTree<EntryMapping> read(Path root, ProgressListener progress) throws IOException, MappingParseException {
			if (!Files.isDirectory(root)) {
				throw new NotDirectoryException(root.toString());
			}

			EntryTree<EntryMapping> mappings = new HashEntryTree<>();

			List<Path> files;
			try (Stream<Path> fileStream = Files.walk(root)) {
				files = fileStream
					.filter(f -> !Files.isDirectory(f))
					.filter(f -> f.toString().endsWith(".mapping"))
					.toList();
			}

			progress.init(files.size(), I18n.translate("progress.mappings.enigma_directory.loading"));
			int step = 0;

			for (Path file : files) {
				progress.step(step++, root.relativize(file).toString());
				if (Files.isHidden(file)) {
					continue;
				}

				readFile(file, mappings);
			}

			return mappings;
		}
	},
	ZIP {
		@Override
		public EntryTree<EntryMapping> read(Path zip, ProgressListener progress) throws MappingParseException, IOException {
			try (FileSystem fs = FileSystems.newFileSystem(zip, (ClassLoader) null)) {
				return DIRECTORY.read(fs.getPath("/"), progress);
			}
		}
	};

	/**
	 * Reads multiple Enigma mapping files.
	 *
	 * @param progress the progress listener
	 * @param paths	the Enigma files to read; cannot be empty
	 * @return the parsed mappings
	 * @throws MappingParseException	if a mapping file cannot be parsed
	 * @throws IOException			  if an IO error occurs
	 * @throws IllegalArgumentException if there are no paths to read
	 */
	public static EntryTree<EntryMapping> readFiles(ProgressListener progress, Path... paths) throws MappingParseException, IOException {
		EntryTree<EntryMapping> mappings = new HashEntryTree<>();

		if (paths.length == 0) {
			throw new IllegalArgumentException("No paths to read mappings from");
		}

		progress.init(paths.length, I18n.translate("progress.mappings.enigma_directory.loading"));
		int step = 0;

		for (Path file : paths) {
			progress.step(step++, paths.toString());
			readFile(file, mappings);
		}

		return mappings;
	}

	private static void readFile(Path path, EntryTree<EntryMapping> mappings) throws IOException, MappingParseException {
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		Deque<MappingPair<?, RawEntryMapping>> mappingStack = new ArrayDeque<>();

		for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
			String line = lines.get(lineNumber);
			int indentation = countIndentation(line);

			line = formatLine(line);
			if (line == null) {
				continue;
			}

			cleanMappingStack(indentation, mappingStack, mappings);

			try {
				MappingPair<?, RawEntryMapping> pair = parseLine(mappingStack.peek(), line);
				if (pair != null) {
					mappingStack.push(pair);
				}
			} catch (Exception e) {
				throw new MappingParseException(path, lineNumber, e);
			}
		}

		// Clean up rest
		cleanMappingStack(0, mappingStack, mappings);
	}

	private static void cleanMappingStack(int indentation, Deque<MappingPair<?, RawEntryMapping>> mappingStack, EntryTree<EntryMapping> mappings) {
		while (indentation < mappingStack.size()) {
			MappingPair<?, RawEntryMapping> pair = mappingStack.pop();
			if (pair.getMapping() != null) {
				mappings.insert(pair.getEntry(), pair.getMapping().bake());
			}
		}
	}

	@Nullable
	private static String formatLine(String line) {
		line = stripComment(line);
		line = line.trim();

		if (line.isEmpty()) {
			return null;
		}

		return line;
	}

	private static String stripComment(String line) {
		//Dont support comments on javadoc lines
		if (line.trim().startsWith(EnigmaFormat.COMMENT)) {
			return line;
		}

		int commentPos = line.indexOf('#');
		if (commentPos >= 0) {
			return line.substring(0, commentPos);
		}

		return line;
	}

	private static int countIndentation(String line) {
		int indent = 0;
		for (int i = 0; i < line.length(); i++) {
			if (line.charAt(i) != '\t') {
				break;
			}

			indent++;
		}

		return indent;
	}

	private static MappingPair<?, RawEntryMapping> parseLine(@Nullable MappingPair<?, RawEntryMapping> parent, String line) {
		String[] tokens = line.trim().split("\\s");
		String keyToken = tokens[0].toUpperCase(Locale.ROOT);
		Entry<?> parentEntry = parent == null ? null : parent.getEntry();

		switch (keyToken) {
			case EnigmaFormat.CLASS -> {
				return parseClass(parentEntry, tokens);
			}
			case EnigmaFormat.FIELD -> {
				return parseField(parentEntry, tokens);
			}
			case EnigmaFormat.METHOD -> {
				return parseMethod(parentEntry, tokens);
			}
			case EnigmaFormat.PARAMETER -> {
				return parseArgument(parentEntry, tokens);
			}
			case EnigmaFormat.COMMENT -> {
				readJavadoc(parent, tokens);
				return null;
			}
			default -> {
				if (keyToken.equals("V1")) {
					throw new RuntimeException("Unknown token '" + keyToken + "' (wrong mappings format?)");
				} else {
					throw new RuntimeException("Unknown token '" + keyToken + "'");
				}
			}
		}
	}

	private static void readJavadoc(MappingPair<?, RawEntryMapping> parent, String[] tokens) {
		if (parent == null) {
			throw new IllegalStateException("Javadoc has no parent!");
		}

		// Empty string to concat
		String jdLine = tokens.length > 1 ? String.join(" ", Arrays.copyOfRange(tokens, 1, tokens.length)) : "";
		if (parent.getMapping() == null) {
			parent.setMapping(new RawEntryMapping(parent.getEntry().getName()));
		}

		parent.getMapping().addJavadocLine(MappingHelper.unescape(jdLine));
	}

	private static MappingPair<ClassEntry, RawEntryMapping> parseClass(@Nullable Entry<?> parent, String[] tokens) {
		String obfuscatedName = ClassEntry.getInnerName(tokens[1]);
		ClassEntry obfuscatedEntry;
		if (parent instanceof ClassEntry classEntry) {
			obfuscatedEntry = new ClassEntry(classEntry, obfuscatedName);
		} else {
			obfuscatedEntry = new ClassEntry(obfuscatedName);
		}

		String mapping = null;
		if (tokens.length == 3) {
			mapping = tokens[2];
		} else if (tokens.length != 2) {
			throw new RuntimeException("invalid class declaration: not enough tokens (" + tokens.length + " found, 2 needed)!");
		}

		return new MappingPair<>(obfuscatedEntry, new RawEntryMapping(mapping));
	}

	private static MappingPair<FieldEntry, RawEntryMapping> parseField(@Nullable Entry<?> parent, String[] tokens) {
		if (!(parent instanceof ClassEntry ownerEntry)) {
			throw new RuntimeException("Field must be a child of a class!");
		}

		String obfuscatedName = tokens[1];
		String mapping = null;
		TypeDescriptor descriptor;

		if (tokens.length == 3) {
			descriptor = new TypeDescriptor(tokens[2]);
		} else if (tokens.length == 4) {
			mapping = tokens[2];
			descriptor = new TypeDescriptor(tokens[3]);
		} else if (tokens.length == 5) {
			mapping = tokens[2];
			descriptor = new TypeDescriptor(tokens[4]);
		} else {
			throw new RuntimeException("Invalid field declaration");
		}

		FieldEntry obfuscatedEntry = new FieldEntry(ownerEntry, obfuscatedName, descriptor);
		return new MappingPair<>(obfuscatedEntry, new RawEntryMapping(mapping));
	}

	private static MappingPair<MethodEntry, RawEntryMapping> parseMethod(@Nullable Entry<?> parent, String[] tokens) {
		if (!(parent instanceof ClassEntry ownerEntry)) {
			throw new RuntimeException("Method must be a child of a class!");
		}

		String obfuscatedName = tokens[1];
		String mapping = null;
		MethodDescriptor descriptor;

		if (tokens.length == 3) {
			descriptor = new MethodDescriptor(tokens[2]);
		} else if (tokens.length == 4 || tokens.length == 5) {
			mapping = tokens[2];
			descriptor = new MethodDescriptor(tokens[3]);
		} else {
			throw new RuntimeException("Invalid method declaration");
		}

		MethodEntry obfuscatedEntry = new MethodEntry(ownerEntry, obfuscatedName, descriptor);
		return new MappingPair<>(obfuscatedEntry, new RawEntryMapping(mapping));
	}

	private static MappingPair<LocalVariableEntry, RawEntryMapping> parseArgument(@Nullable Entry<?> parent, String[] tokens) {
		if (!(parent instanceof MethodEntry ownerEntry)) {
			throw new RuntimeException("Method arg must be a child of a method!");
		}

		LocalVariableEntry obfuscatedEntry = new LocalVariableEntry(ownerEntry, Integer.parseInt(tokens[1]), "", true, null);
		String mapping = tokens[2];

		return new MappingPair<>(obfuscatedEntry, new RawEntryMapping(mapping));
	}
}
