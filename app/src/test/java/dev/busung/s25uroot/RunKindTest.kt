package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two flows are two things, and nothing from one may answer for the other.
 *
 * They look alike enough to be mistaken for one another - the same four steps, the same bar, the same log -
 * and that is exactly why the difference has to be carried as a value rather than assumed. What it was
 * assumed to be was a boolean named after one of the flows, `universal`, which made the **payload** flow the
 * unstated default of every branch. Two things followed from that:
 *
 * - a failure of the universal root offered the payload flow's Retry, and its two answers are "retry in this
 *   boot" - which runs the payload install - and "reboot and retry", which arms one for the boot gate. So a
 *   universal failure could start the other flow's run;
 * - a run read back from its record had no flow in it, so a followed universal run was drawn with the payload
 *   flow's steps and its notification named the second stage with the payload flow's word.
 *
 * The tests here are source properties rather than rendered ones, because both failures are invisible in a
 * diff: a branch is one line, and what it draws is a resource.
 */
class RunKindTest {

    @Test
    fun `an unreadable name is a payload run`() {
        // The default is what a record written before the field existed becomes, and it is the right answer for
        // those: every run the app recorded until then was a payload run. Refusing the record instead would
        // hide a run that happened.
        assertEquals(RunKind.Payload, RunKind.fromName(null))
        assertEquals(RunKind.Payload, RunKind.fromName(""))
        assertEquals(RunKind.Payload, RunKind.fromName("KernelSU"))
        assertEquals(RunKind.Universal, RunKind.fromName("Universal"))
        assertEquals(RunKind.Timer, RunKind.fromName("Timer"))
        assertEquals(RunKind.Payload, RunKind.fromName("Payload"))
    }

    @Test
    fun `the run's flow is a kind and not a flag named after one flow`() {
        val viewModel = source("InstallViewModel.kt")

        assertFalse(
            "the run state is a boolean named after one of the two flows again. That makes the other flow the " +
                "unstated default of every branch, which is what let a universal failure offer the payload " +
                "flow's retry and let a universal run be drawn with the payload flow's steps",
            viewModel.contains("val universal: Boolean"),
        )
        assertTrue("the run no longer carries which flow it is", viewModel.contains("val kind: RunKind"))
    }

    @Test
    fun `each flow's failure is offered its own retry, and neither the other's`() {
        // The bug this exists for: the payload flow's retry entry point was unconditional in the bar, so after
        // a universal failure it was pressed and ran an install of the other kind. The two answers are now two
        // branches, and each has to name its own flow's arming.
        val activity = source("InstallActivity.kt")
        val branch = activity.indexOf("if (universalRun)")
        val otherwise = activity.indexOf("} else {", branch)
        assertTrue("the bar no longer tells the two flows apart", branch > 0 && otherwise > branch)

        // The payload flow's, in the branch that is not the universal run - reaching it is what starts a
        // payload install, so a universal failure must not be able to.
        val payloadRetry = activity.indexOf("showRetryChoice = true")
        assertTrue(
            "the payload flow's retry is reachable without passing the flow's own branch, so a universal " +
                "failure can still start a payload install",
            payloadRetry > otherwise,
        )

        // The universal flow's, inside its own branch, and arming that flow's retry rather than the payload
        // gate's - the two preferences are not interchangeable.
        val universalAnswer = activity.indexOf("onRebootAndRetryUniversal()")
        assertTrue(
            "the universal run's failure has no retry of its own",
            universalAnswer in branch until otherwise,
        )
        assertTrue(
            "the universal retry is not armed by the universal flow's own suspend call, so it would arm the " +
                "payload flow's boot gate",
            source("InstallViewModel.kt").contains("fun armUniversalRetryAfterReboot("),
        )

        // And the other end, where an answer is acted on. Guarding which answers are *offered* is no use if
        // the one that is offered still starts the other flow: the retry handler called `install`, which is
        // the payload flow, so a universal run's retry ran an install of the other kind - and the log and the
        // record that followed described a run nobody had asked for. That is the reported bug, from the side
        // a person actually touches.
        assertTrue(
            "the retry handler starts the payload flow whatever the run was, so one flow's control runs the " +
                "other's",
            activity.contains("onRetry = { installViewModel.retryRun(selectionId) }"),
        )
        assertFalse(
            "the retry handler is unconditional again",
            activity.contains("onRetry = { installViewModel.install(selectionId) }"),
        )
        val viewModel = source("InstallViewModel.kt")
        val retry = viewModel.substringAfter("fun retryRun(").substringBefore("\n    /**")
        .takeIf { it.isNotBlank() }
            ?: error("InstallViewModel no longer has a retryRun, so the retry is not routed by flow")
        assertTrue(
            "the retry no longer tells the two flows apart, so it can start the wrong one",
            retry.contains("if (state.kind != RunKind.Universal)"),
        )
        assertTrue(
            "the universal retry no longer repeats the plan the run itself resolved, so it would repeat the " +
                "last plan any run resolved - or none at all",
            retry.contains("state.universalPlan") &&
                retry.contains("startUniversalRun(plan.flavor, plan.tier)"),
        )
    }

    @Test
    fun `a run read back from its record knows which flow it was`() {
        val history = source("InstallHistory.kt")

        assertTrue(
            "the record does not say which flow the run was, so a screen that did not start it draws the " +
                "payload flow's steps over it",
            history.contains("val kind: RunKind"),
        )
        assertTrue(
            "the record's codec does not write the flow",
            history.contains("""put("kind", entry.kind?.name)"""),
        )
        assertTrue("the record's codec does not read the flow", history.contains("RunKind.fromName("))
        // Absence stays absence rather than becoming a payload run, which is what makes a row able to say
        // nothing about an old record - see the reading of an absent `kind` in InstallHistory.decode.
        assertTrue(
            "the codec can no longer tell a record that does not say from one that says Payload",
            history.contains("value.optionalString(\"kind\")?.let"),
        )
        assertTrue(
            "a followed run does not take its flow from the record",
            source("FollowedRun.kt").contains("entry.kind ?: RunKind.Payload"),
        )
    }

    @Test
    fun `the notification names the stage in the run's own words`() {
        val notification = source("RunNotification.kt")

        assertTrue(
            "the chip is a per-phase word again, which calls the universal root's second stage a download",
            notification.contains("fun chipLabel(phase: InstallPhase, kind: RunKind"),
        )
        assertTrue(
            "nothing varies the stage's word by the flow, so the shade says \"Download\" about a daemon",
            notification.contains("if (kind == RunKind.Universal) R.string.run_chip_daemon"),
        )
    }

    @Test
    fun `each flow is named by the exploit it runs`() {
        // The names as values rather than as words in the UI, because three surfaces read them: the history row
        // a run writes, the subtext of its notification, and the group it was picked from in the sheet. One
        // value per flow is what makes those three agree, and it is what this test can hold without reading a
        // layout.
        assertEquals("CVE-2026-43499", RunKind.Payload.flowName)
        assertEquals("CVE-2026-98260", RunKind.Timer.flowName)
        assertEquals("DirtyFrag (CVE-2026-43284)", RunKind.Universal.flowName)

        // Distinct, which is the point of naming them at all: the payload flows' runs look identical on the
        // next screen - four steps, one bar - and "which one was this" is the question the record has to answer.
        val names = RunKind.entries.map(RunKind::flowName)
        assertEquals(
            "two flows share a name, so a record cannot say which root it was",
            names.size,
            names.toSet().size,
        )

        // And each name carries the number this repository states, rather than a copy of it: the payloads' from
        // the files that document their bugs ([KernelVulnerability] and [TimerVulnerability]), the DirtyFrag
        // one from the constant the helper's own bridge is held to by UniversalRootContractTest.
        assertTrue(RunKind.Payload.flowName.contains(KernelVulnerability.CVE))
        assertTrue(RunKind.Timer.flowName.contains(TimerVulnerability.CVE))
        assertTrue(RunKind.Universal.flowName.contains(UniversalRootRun.CVE))
        assertTrue(RunKind.Universal.flowName.contains(UniversalRootRun.EXPLOIT_NAME))
    }

    @Test
    fun `a timer record written before the timer kind is still named 98260`() {
        // The successful timer run on this phone was recorded as a payload run, because the only two kinds the
        // app had were payload and universal. Its profile id is what still says which payload it was, and the
        // history row reads that rather than naming the legacy 499 bug over it.
        val timer = InstallHistoryEntry(
            id = "timer",
            startedAtMillis = 0,
            completedAtMillis = null,
            result = InstallRunResult.Succeeded,
            log = "",
            profileId = "pa3q-S938U1UEUDDZJB-cve-2026-98260-ksu",
            kind = RunKind.Payload,
        )
        assertEquals(TimerVulnerability.CVE, timer.flowName)
        assertEquals(TimerVulnerability.CVE, timer.copy(kind = RunKind.Timer).flowName)
        assertEquals(KernelVulnerability.CVE, timer.copy(profileId = "pa3q-S938U1UEUDDZJB-ksu").flowName)
        assertEquals(
            "DirtyFrag (CVE-2026-43284)",
            timer.copy(kind = RunKind.Universal).flowName,
        )
    }

    @Test
    fun `every stored run is named by its flow, in the list and in its detail`() {
        val main = source("MainActivity.kt")

        // The list row: named for every entry, with no guard left to drop one of them. The rule was the
        // opposite when the mark was "universal" - only the exceptional flow was marked, because a payload run
        // is what this app does by default - and that stopped being true when the name became the exploit: two
        // runs of one phone, one through the helper and one from this app alone, are the same row otherwise.
        val row = main.substringAfter("private fun HistoryEntryCard(")
            .substringBefore("private fun HistoryDetail(")
        assertTrue(
            "the history row no longer names the flow of the run it draws",
            row.contains("entry.kind?.let {") && row.contains("entry.flowName"),
        )

        // The detail card: the line that says how the run got what it needed. Both flows have one and they are
        // not the same sentence - a payload run went through Shizuku or the helper, and the chain that needs no
        // helper used neither - so a run of one flow must not be described with the other's, which is what the
        // unconditional "Shizuku: not used" did for a universal run.
        val detail = main.substringAfter("private fun HistoryResultCard(")
        assertTrue(
            "the detail card describes a universal run's transport as a payload run's",
            detail.contains("entry.kind == RunKind.Universal") &&
                detail.contains("R.string.history_kind_universal_detail"),
        )
        assertTrue(
            "the payload flow's own transport is no longer stated, so this is a swap and not an addition",
            detail.contains("R.string.history_shizuku_used"),
        )
    }

    @Test
    fun `no run is started without taking the screen from a discovery in flight`() {
        // This one is here because it happened, and it is the subtler half of "one run at a time". The screen
        // on open runs a discovery - a probe and a catalog lookup - which publishes a whole install state when
        // it finishes, and that state carries no flow and no log. It was claimed, and the payload flow claimed
        // against it, but the universal run never did: so a discovery already in flight landed in the middle of
        // a chain run and reset the flow to Payload. The record then said the run was a payload run, the
        // history row named the payload flow's CVE for a DirtyFrag root, and the run's own opening lines were
        // gone. A run therefore claims the screen at its start, whichever flow it is.
        val viewModel = source("InstallViewModel.kt")
        val universal = viewModel.substringAfter("fun startUniversalRun(")
            .substringBefore("\n    /**")
            .takeIf { it.isNotBlank() }
            ?: error("InstallViewModel no longer has a startUniversalRun")
        assertTrue(
            "the universal run does not claim the screen, so a discovery in flight can write over it and " +
                "reset the flow its record is labelled by",
            universal.contains("publishClaim.claim()"),
        )
        assertTrue(
            "the universal run no longer guards against another run of this view model",
            universal.contains("if (installJob?.isActive == true) return"),
        )
        // And a run's log is its own: the record is written from this state, so a log left behind is the
        // previous run's lines filed under the new one.
        val history = viewModel.substringAfter("private fun startHistory()")
            .substringBefore("\n    /**")
        assertTrue(
            "a new run inherits the previous run's log, so its record opens with another run's lines",
            history.contains("log = \"\""),
        )
    }

    @Test
    fun `the boot gate runs an armed universal retry before its own payload questions`() {
        val gate = source("AutoRootService.kt")
        val universal = gate.indexOf("AppPreferences.universalRetryPendingForBoot(this)")
        val payloadDecision = gate.indexOf("AutoRootSupport.decision(this, initialBootToken, kernelSuActive)")

        assertTrue("the boot gate no longer looks for an armed universal retry", universal > 0)
        assertTrue(
            "the universal retry is asked after the payload flow's own gate decision, so a phone that has " +
                "never installed a payload stands down before ever reaching it",
            universal in 0 until payloadDecision,
        )
        assertTrue(
            "the armed retry is not consumed where it runs, so it would run again on every boot after the one " +
                "it was armed for",
            gate.contains("AppPreferences.setUniversalRetryAfterReboot(this, null)"),
        )
        assertTrue(
            "the boot run posts the payload flow's words for the stage, which calls the universal root's " +
                "second stage a download",
            gate.contains("RunNotification.chipLabel(state.phase, RunKind.Universal)"),
        )
    }

    @Test
    fun `the boot receiver counts an armed universal retry as a reason to start the gate`() {
        // The gate's own branch is no use if the receiver never starts it: the receiver stands down unless
        // root-on-boot or a payload retry is armed, and a phone whose only run has been a universal one has
        // both off - so without this the retry would arm, reboot, and do nothing at all.
        val receiver = source("AutoRootBootReceiver.kt")
        assertTrue(
            "the boot receiver does not count an armed universal retry, so a universal-only phone arms a " +
                "retry that never runs",
            receiver.contains("val universalArmed = AppPreferences.universalRetryPendingForBoot(context) != null"),
        )
        // And the chain's standing switch, which is a third reason a boot can have been asked for a run - so
        // the test above and this one are the two halves of the same rule: however a boot was asked, the
        // receiver has to count it or the gate it starts never runs.
        assertTrue(
            "the boot receiver does not count the chain's boot gate, so turning it on would do nothing",
            receiver.contains("val universalBootRoot = AppPreferences.universalBootRoot(context)"),
        )
        assertTrue(
            "a way a boot can be asked for root is missing from the receiver's decision to start the gate",
            receiver.contains(
                "if (!bootRootMode && !retryArmed && !universalArmed && !universalBootRoot)",
            ),
        )
    }

    @Test
    fun `an armed retry belongs to one boot, and carries what it should run`() {
        val prefs = source("AppPreferences.kt")

        assertTrue(
            "the armed retry is not keyed on the boot that armed it, so it would run on every boot",
            prefs.contains("if (armed == AutoRootSupport.currentBootToken()) return null"),
        )
        assertTrue(
            "the retry does not carry the plan it should run, so it could install a KernelSU nobody chose",
            prefs.contains("return universalPlan(context)"),
        )
    }

    @Test
    fun `the two stage vocabularies do not collide, and a record reads either`() {
        assertEquals(RunStage.Transport, FailureStage.fromName("Transport"))
        assertEquals(UniversalStage.Support, FailureStage.fromName("Support"))
        assertEquals(UniversalStage.Payload, FailureStage.fromName("Payload"))
        assertNull("a stage this build does not know is not guessed at", FailureStage.fromName("Teleport"))
        assertNull(FailureStage.fromName(null))

        // The names are what a record stores, so two vocabularies sharing one would read a payload failure back
        // as a universal step - and the two enumerations are different lengths, so the name is the only thing
        // that can tell them apart.
        val names = RunStage.entries.map { it.name } + UniversalStage.entries.map { it.name }
        assertEquals("a name is used by both vocabularies: $names", names.size, names.toSet().size)
    }

    @Test
    fun `each flow's stages land on its own steps, and the payload flow's mapping is unchanged`() {
        // The step marks and the bar's fraction come from these. They were one `when` over the payload
        // vocabulary, which a universal stage had no entry in at all.
        assertEquals(0, UniversalStage.Support.stepIndex)
        assertEquals(1, UniversalStage.Payload.stepIndex)
        assertEquals(2, UniversalStage.Chain.stepIndex)
        assertEquals(3, UniversalStage.Load.stepIndex)

        assertEquals(0, RunStage.Transport.stepIndex)
        assertEquals(0, RunStage.Target.stepIndex)
        assertEquals(1, RunStage.Download.stepIndex)
        assertEquals(2, RunStage.Exploit.stepIndex)
        assertEquals(3, RunStage.KernelSu.stepIndex)
        assertEquals(3, RunStage.Verify.stepIndex)

        // And the two go through one function, which no longer knows a vocabulary at all.
        assertEquals(RunStage.Verify.stepIndex, installerStepForStage(RunStage.Verify))
        assertEquals(UniversalStage.Load.stepIndex, installerStepForStage(UniversalStage.Load))
    }

    @Test
    fun `a universal failure names one of its own steps`() {
        val viewModel = source("InstallViewModel.kt")
        val body = viewModel.substringAfter("private fun failUniversal(")

        assertTrue(
            "a universal failure carries no stage again, so the card, the step marks, the bar and the record " +
                "have nothing to place it on",
            body.contains("RunFailure.of(stage, reason)"),
        )
        assertTrue(
            "a universal failure is not recorded with the step it stopped in",
            body.contains("failureStage = stage"),
        )
        assertTrue(
            "no universal failure names a step, so there is no vocabulary to keep its own",
            viewModel.contains("failUniversal(UniversalStage."),
        )
        assertFalse(
            "a universal failure is placed on one of the payload flow's steps - Transport and Target describe " +
                "resolving a payload and carrying it, which this flow does neither of",
            viewModel.contains("failUniversal(RunStage."),
        )
    }

    @Test
    fun `the step list is chosen by the flow`() {
        assertTrue(
            "the steps are picked by something other than the run's flow, so one flow's wording is drawn " +
                "over the other's run",
            source("InstallActivity.kt")
                .contains("if (kind == RunKind.Universal) universalInstallerSteps else installerSteps"),
        )
    }

    private fun source(name: String): String {
        val file = candidateRoots()
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name == name }.toList() }
            .firstOrNull()
        requireNotNull(file) { "$name was not found; the scan is looking at the wrong directory" }
        return file.readText()
    }

    private fun candidateRoots(): List<File> = listOf(
        File("src/main/java"),
        File("app/src/main/java"),
    ).filter(File::isDirectory)
}
