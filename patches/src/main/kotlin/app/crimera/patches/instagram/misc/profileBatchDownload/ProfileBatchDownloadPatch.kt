package app.crimera.patches.instagram.misc.profileBatchDownload

import app.crimera.patches.instagram.misc.batchDownload.batchGridHooksPatch
import app.crimera.patches.instagram.misc.batchDownload.GridBinder
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.p0Register
import com.android.tools.smali.dexlib2.Opcode

private const val PROFILE = "Lcom/instagram/profile/fragment/UserDetailFragment;"
private const val HOOK = "Lapp/morphe/extension/instagram/patches/batchDownload/BatchDownloadManager;"
private const val SESSION = "Lcom/instagram/common/session/UserSession;"
private object ProfileCreated : Fingerprint(
    definingClass = PROFILE, name = "onViewCreated", returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
    strings = listOf("UserDetailFragment.onViewCreated"),
)
private object ProfileDestroyed : Fingerprint(
    definingClass = PROFILE, name = "onDestroyView", returnType = "V", parameters = emptyList(),
)

private object ProfileConfigureActionBar : Fingerprint(
    definingClass = PROFILE, name = "AeX", returnType = "V", parameters = listOf("LX/00KB;"),
    strings = listOf("UserDetailFragment.configureActionBar"),
)

@Suppress("unused")
val profileBatchDownloadPatch = patch(
    name = "Profile Batch Downloader",
    description = "Adds isolated profile post selection, Select All Loaded and complete-post batch downloads.",
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    dependsOn(batchGridHooksPatch)
    execute {
        ProfileCreated.method.apply {
            if (p0Register < 3) throw PatchException("Profile attach requires three local registers")
            implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
                .map { it.index }.reversed().forEach { index ->
                    addInstructions(index, """
                        invoke-virtual/range {p0 .. p0}, LX/0CTn;->getSession()$SESSION
                        move-result-object v1
                        move-object/from16 v0, p1
                        move-object/from16 v2, p0
                        invoke-static {v0, v1, v2}, $HOOK->attachProfile(Landroid/view/View;${SESSION}Ljava/lang/Object;)V
                    """.trimIndent())
                }
        }
        ProfileConfigureActionBar.method.apply {
            implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
                .map { it.index }.reversed().forEach { index ->
                    addInstructions(index, "invoke-static/range {p0 .. p0}, $HOOK->profileToolbar(Ljava/lang/Object;)V")
                }
        }
        ProfileDestroyed.method.apply {
            if (p0Register < 1) throw PatchException("Profile detach requires a local register")
            addInstructions(0, """
                invoke-virtual/range {p0 .. p0}, Landroidx/fragment/app/Fragment;->getView()Landroid/view/View;
                move-result-object v0
                invoke-static {v0}, $HOOK->detach(Landroid/view/View;)V
            """.trimIndent())
        }
        // Verified A04 forwards p1 unchanged to native listener installation in A01.
        GridBinder.method.apply {
            if (p0Register < 3) throw PatchException("Profile listener wrapper requires three local registers")
            addInstructions(0, """
                move-object/from16 v0, p9
                move-object/from16 v1, p6
                move-object/from16 v2, p1
                invoke-static {v0, v1, v2}, $HOOK->profileClick(Landroid/view/View;Ljava/lang/Object;Landroid/view/View${'$'}OnClickListener;)Landroid/view/View${'$'}OnClickListener;
                move-result-object v0
                move-object/16 p1, v0
            """.trimIndent())
        }
    }
}
