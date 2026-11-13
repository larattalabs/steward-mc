package dev.larattalabs.steward.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * A module lot's binding (docs/MODULES.md 4.2 step 4), saved with its build (BuildStore format 5, {@code moduleBindings}: lotId -> binding)
 * before the realise: which catalog version and program the lot holds, the Architect entry made for it, and the exact operation key
 * that made it, so a restart finds the variant again ({@code Designs.variantByKey(owner, opKey)}) and never makes a second one.
 *
 * @param moduleId the catalog id ({@code crop_farm})
 * @param version the catalog version
 * @param programSha sha256 of the program's exact source (Architect's {@code givenProgram.sha})
 * @param params the canonical params (sorted keys) the variant was made with
 * @param entryId the variant's library entry, null until Architect answers (the key finds it)
 * @param bibleId the bible the variant was re-skinned with, null for the program's own palette
 * @param bibleVersion that bible's exact version as sent (resolved, never "latest"; 0 only when there is no bible)
 * @param geometrySha {@link Canon#sha} of the lot's box, frame and irrigation cells: a re-plan that changes it makes the variant again
 * @param opKey the key the variant was asked under, exactly as sent ({@code steward.<build>.module.<lotId>}, {@code .<n>} after a re-plan)
 * @param owner the key's owner scope, exactly as sent
 */
public record ModuleBinding(String moduleId, int version, String programSha, JsonObject params, @Nullable String entryId, @Nullable String bibleId,
	int bibleVersion, String geometrySha, String opKey, @Nullable String owner) {
	public ModuleBinding {
		// a missing params stays missing: BuildStore refuses such a binding rather than read it as {} (other params under the same key)
		params = params == null ? null : Canon.sorted(params).getAsJsonObject();
	}

	/** The lot ext Architect keeps on the placed lot: {@code steward_mc:module {id, version, programSha, params}}. */
	public ModuleRef ref() {
		return new ModuleRef(moduleId, version, programSha, params);
	}

	/** The same binding once its variant's entry id is known. */
	public ModuleBinding withEntry(String entryId) {
		return new ModuleBinding(moduleId, version, programSha, params, entryId, bibleId, bibleVersion, geometrySha, opKey, owner);
	}

	/** Whether this binding still holds for a lot of this geometry (else the variant is made again under a new key). */
	public boolean fits(@Nullable JsonElement geometry) {
		return geometrySha.equals(Canon.sha(geometry));
	}
}
