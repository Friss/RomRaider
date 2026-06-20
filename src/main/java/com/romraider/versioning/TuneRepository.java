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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.log4j.Logger;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import com.romraider.maps.Rom;

/**
 * Thin wrapper around JGit that manages a single git repository whose working
 * directory holds a {@link TuneSnapshot}. All git plumbing for the tune
 * versioning feature lives here so the rest of the editor stays git-agnostic.
 *
 * Typical lifecycle:
 * <pre>
 *   TuneRepository repo = TuneRepository.initOrOpen(dir);
 *   try {
 *       repo.commit(rom, "Lowered idle target", "Zach");
 *       List&lt;TuneCommit&gt; history = repo.log();
 *   } finally {
 *       repo.close();
 *   }
 * </pre>
 */
public final class TuneRepository {

    private static final Logger LOGGER = Logger.getLogger(TuneRepository.class);
    private static final String GITATTRIBUTES_FILE = ".gitattributes";
    private static final String GITATTRIBUTES =
            "# Managed by RomRaider tune versioning\n" +
            "tune.bin binary\n" +
            "*.txt text\n";

    /** The only paths this feature stages/commits in the repository. */
    private static final String[] SNAPSHOT_PATHS = {
            TuneSnapshot.BIN_FILE, TuneSnapshot.MANIFEST_FILE,
            TuneSnapshot.TABLES_DIR, GITATTRIBUTES_FILE };

    private final Git git;
    private final File workTree;

    private TuneRepository(Git git, File workTree) {
        this.git = git;
        this.workTree = workTree;
    }

    /**
     * Open the repository at {@code dir}, creating (git init) it if one does not
     * already exist.
     */
    public static TuneRepository initOrOpen(File dir) throws IOException, GitAPIException {
        if (dir == null) {
            throw new IllegalArgumentException("dir is null");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create repository directory: " + dir);
        }

        final File gitDir = new File(dir, ".git");
        if (gitDir.isDirectory()) {
            final Repository repository = new FileRepositoryBuilder()
                    .setGitDir(gitDir)
                    .readEnvironment()
                    .build();
            return new TuneRepository(new Git(repository), dir);
        }

        final Git git = Git.init().setDirectory(dir).call();
        writeGitAttributes(dir);
        return new TuneRepository(git, dir);
    }

    /** @return true if a git repository already exists at {@code dir}. */
    public static boolean exists(File dir) {
        return dir != null && new File(dir, ".git").isDirectory();
    }

    /**
     * Snapshot {@code rom} and commit it, if anything actually changed.
     *
     * Change detection is done from the side-effect-free part of the snapshot
     * (the readable table tree + manifest). Only when that shows a real edit do
     * we write the binary, because {@link Rom#saveFile()} re-stamps the edit
     * count/checksum into checksum-fix tunes on every call -- writing it
     * unconditionally would defeat no-op detection and silently mutate the open
     * tune.
     *
     * @return the resulting commit, or {@code null} if there was nothing to
     *         commit (no edits since the last version).
     */
    public TuneCommit commit(Rom rom, String message, String author)
            throws IOException, GitAPIException {
        // Phase 1: write only the no-side-effect artifacts and stage them.
        TuneSnapshot.writeReadable(rom, workTree);
        stageSnapshotPaths();

        // If the readable tree + manifest are unchanged from the last commit,
        // there were no real edits. The binary is left untouched (so the open
        // tune is not re-stamped) and no empty commit is created.
        if (hasCommits() && git.status().call().isClean()) {
            LOGGER.info("Nothing to commit for tune repository " + workTree);
            return null;
        }

        // Phase 2: a real change (or the initial import) -- write the canonical
        // binary too and stage it, then commit the whole snapshot.
        TuneSnapshot.writeBinary(rom, workTree);
        stageSnapshotPaths();

        final PersonIdent ident = identFor(author);
        final RevCommit revCommit = git.commit()
                .setMessage(message == null || message.trim().length() == 0
                        ? "Update tune" : message)
                .setAuthor(ident)
                .setCommitter(ident)
                .call();

        return toTuneCommit(revCommit);
    }

    /**
     * Stage only the snapshot paths this feature owns, so that selecting an
     * existing/shared directory as the repo never sweeps in unrelated files.
     * Two passes: the first stages adds/modifications, the second (update)
     * stages deletions of tracked files (e.g. a removed table .txt).
     */
    private void stageSnapshotPaths() throws GitAPIException {
        final AddCommand add = git.add();
        final AddCommand addUpdate = git.add().setUpdate(true);
        for (String path : SNAPSHOT_PATHS) {
            add.addFilepattern(path);
            addUpdate.addFilepattern(path);
        }
        add.call();
        addUpdate.call();
    }

    /** @return commit history, newest first. */
    public List<TuneCommit> log() throws GitAPIException, IOException {
        final List<TuneCommit> commits = new ArrayList<TuneCommit>();
        if (!hasCommits()) {
            return commits;
        }
        for (RevCommit revCommit : git.log().call()) {
            commits.add(toTuneCommit(revCommit));
        }
        return commits;
    }

    /** @return the raw bytes of {@code tune.bin} as it existed at {@code commitId}. */
    public byte[] readBinaryAt(String commitId) throws IOException {
        final Repository repo = git.getRepository();
        final ObjectId revId = repo.resolve(commitId + "^{tree}");
        if (revId == null) {
            throw new IOException("Could not resolve commit: " + commitId);
        }
        final org.eclipse.jgit.treewalk.TreeWalk walk =
                org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, TuneSnapshot.BIN_FILE, revId);
        if (walk == null) {
            throw new IOException(TuneSnapshot.BIN_FILE + " not found in commit " + commitId);
        }
        try {
            final ObjectId blobId = walk.getObjectId(0);
            return repo.open(blobId).getBytes();
        } finally {
            walk.release();
        }
    }

    /** @return a unified diff between two commits across the whole tree. */
    public String diff(String oldCommit, String newCommit) throws IOException, GitAPIException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final DiffFormatter formatter = new DiffFormatter(out);
        try {
            formatter.setRepository(git.getRepository());
            final AbstractTreeIterator oldTree = treeIterator(oldCommit);
            final AbstractTreeIterator newTree = treeIterator(newCommit);
            formatter.format(formatter.scan(oldTree, newTree));
            formatter.flush();
            return out.toString("UTF-8");
        } finally {
            formatter.release();
        }
    }

    public void close() {
        // Git.close() also closes the underlying repository in JGit 3.x.
        git.close();
    }

    // ----------------------------------------------------------------------

    private boolean hasCommits() throws IOException {
        return git.getRepository().resolve("HEAD") != null;
    }

    private AbstractTreeIterator treeIterator(String commitId) throws IOException {
        final Repository repo = git.getRepository();
        final ObjectId treeId = repo.resolve(commitId + "^{tree}");
        if (treeId == null) {
            throw new IOException("Could not resolve commit: " + commitId);
        }
        final CanonicalTreeParser parser = new CanonicalTreeParser();
        final org.eclipse.jgit.lib.ObjectReader reader = repo.newObjectReader();
        try {
            parser.reset(reader, treeId);
        } finally {
            reader.release();
        }
        return parser;
    }

    private PersonIdent identFor(String author) {
        final String name = (author == null || author.trim().length() == 0)
                ? "RomRaider" : author.trim();
        return new PersonIdent(name, "");
    }

    private TuneCommit toTuneCommit(RevCommit c) {
        final PersonIdent ident = c.getAuthorIdent();
        return new TuneCommit(
                c.getName(),
                c.abbreviate(8).name(),
                ident == null ? "" : ident.getName(),
                new Date(c.getCommitTime() * 1000L),
                c.getShortMessage());
    }

    private static void writeGitAttributes(File dir) {
        final File attrs = new File(dir, GITATTRIBUTES_FILE);
        if (attrs.exists()) {
            return;
        }
        try {
            final BufferedWriter w = new BufferedWriter(new FileWriter(attrs));
            try {
                w.write(GITATTRIBUTES);
            } finally {
                w.close();
            }
        } catch (IOException ex) {
            LOGGER.warn("Unable to write .gitattributes", ex);
        }
    }
}
