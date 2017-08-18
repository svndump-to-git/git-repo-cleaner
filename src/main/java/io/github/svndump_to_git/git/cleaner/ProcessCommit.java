package io.github.svndump_to_git.git.cleaner;

import java.io.IOException;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

public interface ProcessCommit {

	ObjectId processCommit(RevCommit commit) throws MissingObjectException, IncorrectObjectTypeException, IOException;

	void setupRevWalkSortAndFilters(RevWalk walkRepo);
	
}