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

import static javax.swing.JOptionPane.ERROR_MESSAGE;
import static javax.swing.JOptionPane.YES_NO_OPTION;
import static javax.swing.JOptionPane.YES_OPTION;
import static javax.swing.JOptionPane.showConfirmDialog;
import static javax.swing.JOptionPane.showMessageDialog;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.WindowConstants;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import com.romraider.editor.ecu.ECUEditor;
import com.romraider.maps.Rom;
import com.romraider.swing.CompareImagesForm;
import com.romraider.xml.DOMRomUnmarshaller;

/**
 * Modal dialog that shows the commit history of a tune's {@link TuneRepository},
 * a live per-commit diff, and lets the user restore a previous version as a new
 * image in the editor.
 */
public final class TuneHistoryDialog extends JDialog {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(TuneHistoryDialog.class);

    private final transient TuneRepository repo;
    private final transient Rom rom;
    private final transient ECUEditor editor;
    private final transient List<TuneCommit> commits;

    private final JTextArea diffArea = new JTextArea();

    public TuneHistoryDialog(Frame owner, TuneRepository repo, Rom rom, ECUEditor editor)
            throws Exception {
        super(owner, "Tune History - " + rom.getFileName(), true);
        this.repo = repo;
        this.rom = rom;
        this.editor = editor;
        this.commits = repo.log();

        buildUi();
        setSize(720, 520);
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        final JTable table = new JTable(new CommitTableModel());
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(130);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(380);

        diffArea.setEditable(false);
        diffArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(table), new JScrollPane(diffArea));
        split.setResizeWeight(0.45);
        add(split, BorderLayout.CENTER);

        final JButton compareButton = new JButton("Compare to Working Tune ...");
        compareButton.setEnabled(false);
        final JButton restoreButton = new JButton("Restore Selected Version ...");
        restoreButton.setEnabled(false);
        final JButton closeButton = new JButton("Close");

        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(compareButton);
        buttons.add(restoreButton);
        buttons.add(closeButton);
        add(buttons, BorderLayout.SOUTH);

        table.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                if (e.getValueIsAdjusting()) {
                    return;
                }
                final int row = table.getSelectedRow();
                restoreButton.setEnabled(row >= 0);
                compareButton.setEnabled(row >= 0);
                showDiffForRow(row);
            }
        });

        compareButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                compareToWorkingTune(commits.get(table.getSelectedRow()));
            }
        });

        restoreButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                restore(commits.get(table.getSelectedRow()));
            }
        });

        closeButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });

        if (!commits.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private void showDiffForRow(int row) {
        if (row < 0 || row >= commits.size()) {
            diffArea.setText("");
            return;
        }
        final TuneCommit selected = commits.get(row);
        // commits are newest-first, so the parent is the next row down.
        final boolean hasParent = row + 1 < commits.size();
        try {
            if (!hasParent) {
                diffArea.setText("(initial commit - no previous version to diff against)");
            } else {
                final TuneCommit parent = commits.get(row + 1);
                final String diff = repo.diff(parent.getId(), selected.getId());
                diffArea.setText(diff.length() == 0 ? "(no textual changes)" : diff);
            }
            diffArea.setCaretPosition(0);
        } catch (Exception ex) {
            LOGGER.error("Error computing diff", ex);
            diffArea.setText("Error computing diff: " + ex.getMessage());
        }
    }

    private void restore(TuneCommit commit) {
        final File restored = new File(sourceDir(rom), baseName(rom) + "_" + commit.getShortId() + ".bin");

        final int answer = showConfirmDialog(this,
                "Restore tune.bin from commit " + commit.getShortId() + " to:\n"
                        + restored.getAbsolutePath() + "\n\nThe restored image will be opened in the editor.",
                "Restore Tune Version", YES_NO_OPTION);
        if (answer != YES_OPTION) {
            return;
        }

        try {
            final byte[] data = repo.readBinaryAt(commit.getId());
            final FileOutputStream fos = new FileOutputStream(restored);
            try {
                fos.write(data);
            } finally {
                fos.close();
            }
            editor.openImage(restored);
            dispose();
        } catch (Exception ex) {
            LOGGER.error("Error restoring tune version", ex);
            showMessageDialog(this, "Unable to restore version: " + ex.getMessage(),
                    "Restore Failed", ERROR_MESSAGE);
        }
    }

    /**
     * Load the selected commit's tune.bin as a separate {@link Rom} (using the
     * working tune's definition), add it to the editor and open the existing
     * image-compare form preselected against the current working tune.
     */
    private void compareToWorkingTune(TuneCommit commit) {
        try {
            final byte[] data = repo.readBinaryAt(commit.getId());
            final Rom historical = loadHistoricalRom(data, commit.getShortId());
            if (historical == null) {
                showMessageDialog(this,
                        "Could not load the historical tune with the current definition.",
                        "Compare Failed", ERROR_MESSAGE);
                return;
            }

            editor.addRom(historical);
            editor.refreshAfterNewRom();

            final CompareImagesForm form =
                    new CompareImagesForm(editor.getImages(), editor.getIconImage());
            form.setLocationRelativeTo(this);
            form.setVisible(true);
            form.setComparison(rom, historical);
            dispose();
        } catch (Exception ex) {
            LOGGER.error("Error comparing tune version", ex);
            showMessageDialog(this, "Unable to compare version: " + ex.getMessage(),
                    "Compare Failed", ERROR_MESSAGE);
        }
    }

    /** Build a Rom from historical bytes reusing the working tune's definition. */
    private Rom loadHistoricalRom(byte[] data, String label) throws Exception {
        final Document doc = rom.getDocument();
        final File defPath = rom.getDefinitionPath();
        if (doc == null || defPath == null) {
            return null;
        }

        final DOMRomUnmarshaller unmarshaller = new DOMRomUnmarshaller();
        Node romNode = unmarshaller.checkDefinitionMatch(doc.getDocumentElement(), data);
        if (romNode == null) {
            romNode = DOMRomUnmarshaller.findFirstRomNode(doc.getDocumentElement());
        }
        if (romNode == null) {
            return null;
        }

        final Rom historical = unmarshaller.unmarshallXMLDefinition(
                defPath, doc.getDocumentElement(), romNode, data, editor.getStatusPanel());
        if (historical == null) {
            return null;
        }
        historical.setDocument(doc);
        historical.setDefinitionPath(defPath);

        // Give it a distinct display name (drives the tree + compare combo boxes).
        final String baseName = stripExtension(rom.getFileName());
        historical.setFullFileName(new File(baseName + "@" + label + ".bin"));

        historical.populateTables(data, editor.getStatusPanel());
        return historical;
    }

    private static String stripExtension(String name) {
        final int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private final class CommitTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] columns = {"Commit", "Date", "Author", "Message"};
        // SimpleDateFormat is not thread-safe; keep one per model, used on the EDT.
        private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        @Override
        public int getRowCount() {
            return commits.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            final TuneCommit c = commits.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return c.getShortId();
                case 1:
                    return c.getWhen() == null ? "" : dateFmt.format(c.getWhen());
                case 2:
                    return c.getAuthor();
                case 3:
                    return c.getMessage();
                default:
                    return "";
            }
        }
    }

    /** Convenience: returns a preferred default repository directory for a tune. */
    public static File defaultRepoDir(Rom rom) {
        return new File(sourceDir(rom), baseName(rom) + ".tunehistory");
    }

    /** The tune's file name without extension, used to name repo/restore files. */
    private static String baseName(Rom rom) {
        final File source = rom.getFullFileName();
        return stripExtension(source == null ? rom.getFileName() : source.getName());
    }

    /** The directory the tune lives in (or the working dir if unsaved). */
    private static File sourceDir(Rom rom) {
        final File source = rom.getFullFileName();
        return source == null ? new File(".") : source.getParentFile();
    }
}
