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

import java.util.Date;

/**
 * Immutable view of a single commit in a {@link TuneRepository}, suitable for
 * display in a history table.
 */
public final class TuneCommit {

    private final String id;
    private final String shortId;
    private final String author;
    private final Date when;
    private final String message;

    public TuneCommit(String id, String shortId, String author, Date when, String message) {
        this.id = id;
        this.shortId = shortId;
        this.author = author;
        this.when = when == null ? null : new Date(when.getTime());
        this.message = message;
    }

    /** Full 40-character commit SHA-1. */
    public String getId() {
        return id;
    }

    /** Abbreviated commit SHA-1 for display. */
    public String getShortId() {
        return shortId;
    }

    public String getAuthor() {
        return author;
    }

    public Date getWhen() {
        return when == null ? null : new Date(when.getTime());
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return shortId + "  " + message;
    }
}
