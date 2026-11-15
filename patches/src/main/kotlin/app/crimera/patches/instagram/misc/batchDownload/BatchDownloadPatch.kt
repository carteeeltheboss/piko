package app.crimera.patches.instagram.misc.batchDownload

import app.crimera.patches.instagram.misc.download.downloadMediaPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.p0Register
import com.android.tools.smali.dexlib2.Opcode

private const val HOOK = "Lapp/morphe/extension/instagram/patches/batchDownload/BatchDownloadManager;"
private const val SESSION = "Lcom/instagram/common/session/UserSession;"

// Verified against the declared arm64 Instagram 452.1.0.53.64 APK (385811006).
private object SavedFragment : Fingerprint(
    name = "A0B", definingClass = "LX/0RLB;", returnType = "LX/0Czy;", strings = listOf("saveGrid"),
)
private object GridBinder : Fingerprint(
    definingClass = "LX/0DNO;", name = "A04", returnType = "V",
    strings = listOf("MediaGridItemViewBinder.bindMediaView"),
)
private object SavedViewCreated : Fingerprint(
    classFingerprint = SavedFragment, name = "onViewCreated", returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
)
private object SavedViewDestroyed : Fingerprint(
    classFingerprint = SavedFragment, name = "onDestroyView", returnType = "V", parameters = emptyList(),
)
private object SavedClick : Fingerprint(
    classFingerprint = SavedFragment, name = "Fc9", returnType = "V",
    parameters = listOf("Landroid/view/View;", "LX/050F;", "LX/0D0U;", "Lcom/instagram/feed/media/Media;"),
)

@Suppress("unused")
val savedPostsBatchDownloadPatch = patch(
    name = "Saved Posts Batch Downloader",
    description = "Adds manual multi-selection and queued full-post downloads to Saved collections.",
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    dependsOn(downloadMediaPatch)
    execute {
        val owner = SavedFragment.originalClassDef
        val session = owner.fields.singleOrNull { it.type == SESSION }
            ?: throw PatchException("Expected one Saved screen session field")
        SavedViewCreated.method.apply {
            if (p0Register < 3) throw PatchException("Saved view hook needs three local registers")
            implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
                .map { it.index }.reversed().forEach { index ->
                    addInstructions(index, """
                        move-object/from16 v0, p0
                        iget-object v1, v0, $session
                        move-object/from16 v0, p1
                        move-object/from16 v2, p0
                        invoke-static {v0, v1, v2}, $HOOK->attach(Landroid/view/View;${SESSION}Ljava/lang/Object;)V
                    """.trimIndent())
                }
        }
        SavedViewDestroyed.method.apply {
            if (p0Register < 1) throw PatchException("Saved cleanup hook needs a local register")
            addInstructions(0, """
                invoke-virtual/range {p0 .. p0}, Landroidx/fragment/app/Fragment;->getView()Landroid/view/View;
                move-result-object v0
                invoke-static {v0}, $HOOK->detach(Landroid/view/View;)V
            """.trimIndent())
        }
        SavedClick.method.apply {
            if (p0Register < 2) throw PatchException("Saved click hook needs two locals")
            addInstructionsWithLabels(0, """
                move-object/from16 v0, p1
                move-object/from16 v1, p4
                invoke-static {v0, v1}, $HOOK->select(Landroid/view/View;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :native
                return-void
            """.trimIndent(), ExternalLabel("native", getInstruction(0)))
        }
        GridBinder.method.apply {
            if (p0Register < 2 || parameterTypes.getOrNull(5) != "Lcom/instagram/feed/media/Media;" ||
                parameterTypes.getOrNull(8) != "Lcom/instagram/igds/components/imagebutton/IgImageButton;") {
                throw PatchException("Unexpected Saved thumbnail binder parameters")
            }
            addInstructions(0, """
                move-object/from16 v0, p9
                move-object/from16 v1, p6
                invoke-static {v0, v1}, $HOOK->bind(Landroid/view/View;Ljava/lang/Object;)V
            """.trimIndent())
        }
    }
}
