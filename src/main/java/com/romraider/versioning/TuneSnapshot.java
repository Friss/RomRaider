/*
 * RomRaider Open-Source Tuning, Logging and Reflashing
 * Copyright (C) 2006-2026 RomRaider.com
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.romraider.versioning;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;

import org.apache.log4j.Logger;

import com.romraider.maps.Rom;
import com.romraider.maps.RomID;
import com.romraider.maps.Table;
import com.romraider.swing.TableTreeNode;

/**
 * Writes a {@link Rom} to a working-directory layout that is friendly to a
 * version-control system (git):
 *
 * <pre>
 *   &lt;repo&gt;/
 *     tune.bin              canonical binary (source of truth, restorable)
 *     manifest.txt          RomID metadata for human + machine reading
 *     tables/
 *       &lt;Category&gt;/
 *         &lt;Table Name&gt;.txt one readable text export per table
 * </pre>
 *
 * The text tree gives meaningful, line-level diffs per table while the binary
 * remains the authoritative, flashable artifact.
 */
public final class TuneSnapshot {

    private static final Logger LOGGER = Logger.getLogger(TuneSnapshot.class);
    private static final String SEP = System.getProperty("file.separator");
    private static final String NL = System.getProperty("line.separator");

    public static final String BIN_FILE = "tune.bin";
    public static final String MANIFEST_FILE = "manifest.txt";
    public static final String TABLES_DIR = "tables";

    private TuneSnapshot() {
    }

    /**
     * Write the side-effect-free part of the snapshot: the readable per-table
     * text tree and the metadata manifest. Neither touches the tune's bytes, so
     * this is safe to call purely to detect whether anything actually changed
     * (see {@link TuneRepository#commit}).
     *
     * @param rom     the tune to serialize
     * @param repoDir the repository working directory (will be created)
     * @throws IOException on any write failure
     */
    public static void writeReadable(Rom rom, File repoDir) throws IOException {
        if (rom == null) {
            throw new IllegalArgumentException("rom is null");
        }
        if (!repoDir.exists() && !repoDir.mkdirs()) {
            throw new IOException("Unable to create repository directory: " + repoDir);
        }

        writeManifest(rom, repoDir);
        writeTables(rom, repoDir);
    }

    /**
     * Write the canonical binary. This calls {@link Rom#saveFile()}, which for
     * checksum-fix tunes re-stamps the edit count/checksum in the tune's bytes,
     * so it must only be called when a commit is actually going to happen.
     */
    public static void writeBinary(Rom rom, File repoDir) throws IOException {
        // saveFile() applies checksum fix-ups and returns the authoritative bytes.
        final byte[] output = rom.saveFile();
        final File binFile = new File(repoDir, BIN_FILE);
        final FileOutputStream fos = new FileOutputStream(binFile);
        try {
            fos.write(output);
        } finally {
            fos.close();
        }
    }

    private static void writeManifest(Rom rom, File repoDir) throws IOException {
        final RomID id = rom.getRomID();
        final StringBuilder sb = new StringBuilder();
        if (id != null) {
            appendField(sb, "xmlid", id.getXmlid());
            appendField(sb, "internalIdString", id.getInternalIdString());
            appendField(sb, "ecuId", id.getEcuId());
            appendField(sb, "caseId", id.getCaseId());
            appendField(sb, "make", id.getMake());
            appendField(sb, "market", id.getMarket());
            appendField(sb, "model", id.getModel());
            appendField(sb, "subModel", id.getSubModel());
            appendField(sb, "transmission", id.getTransmission());
            appendField(sb, "year", id.getYear());
            appendField(sb, "author", id.getAuthor());
            appendField(sb, "version", id.getVersion());
            // NB: editStamp is intentionally omitted. Rom.saveFile() rewrites it
            // on every save for checksum-fix tunes, so including it here would
            // make the manifest churn each commit and defeat no-op detection.
            // The stamp is preserved in tune.bin regardless.
            appendField(sb, "checksum", id.getChecksum());
            appendField(sb, "fileSize", Integer.toString(id.getFileSize()));
        }

        final File manifest = new File(repoDir, MANIFEST_FILE);
        final BufferedWriter out = new BufferedWriter(new FileWriter(manifest));
        try {
            out.write(sb.toString());
            out.flush();
        } finally {
            close(out);
        }
    }

    private static void writeTables(Rom rom, File repoDir) throws IOException {
        final File tablesDir = new File(repoDir, TABLES_DIR);
        // Start clean so removed tables don't survive between commits.
        deleteRecursively(tablesDir);
        if (!tablesDir.exists() && !tablesDir.mkdirs()) {
            throw new IOException("Unable to create tables directory: " + tablesDir);
        }

        for (TableTreeNode treeNode : rom.getTableNodes().values()) {
            final Table table = treeNode.getTable();
            final String category = sanitize(table.getCategory());
            final String tableName = sanitize(table.getName());

            final File categoryDir = new File(tablesDir, category);
            if (!categoryDir.exists() && !categoryDir.mkdirs()) {
                throw new IOException("Unable to create category directory: " + categoryDir);
            }

            final File tableFile = new File(categoryDir, tableName + ".txt");
            final BufferedWriter out = new BufferedWriter(new FileWriter(tableFile));
            try {
                out.write(table.getTableAsString().toString());
                out.flush();
            } finally {
                close(out);
            }
        }
    }

    private static void appendField(StringBuilder sb, String key, String value) {
        sb.append(key).append('=').append(value == null ? "" : value).append(NL);
    }

    /** Replace path separators so category/table names can't escape the tree. */
    private static String sanitize(String name) {
        if (name == null) {
            return "unnamed";
        }
        return name.replace('/', '-').replace('\\', '-').replace(SEP, "-");
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            LOGGER.warn("Unable to delete " + file);
        }
    }

    private static void close(BufferedWriter out) {
        try {
            out.close();
        } catch (IOException ex) {
            LOGGER.warn("Error closing writer", ex);
        }
    }
}
