/* File: h-system.h */

#ifndef INCLUDED_H_SYSTEM_H
#define INCLUDED_H_SYSTEM_H

/*
 * Include the basic "system" files.
 *
 * Make sure all "system" constants/macros are defined.
 * Make sure all "system" functions have "extern" declarations.
 *
 * This file is a big hack to make other files less of a hack.
 * This file has been rebuilt -- it may need a little more work.
 */

#include <stdio.h>
#include <ctype.h>
#include <errno.h>
#include <stdlib.h>

#ifdef SET_UID

#include <sys/types.h>

#if defined(__linux__)
#include <sys/time.h>
#endif

#ifndef __ANDROID__
#include <sys/timeb.h>
#endif

#endif /* SET_UID */

#include <time.h>

#if defined(WINDOWS)
#include <io.h>
#endif

#include <memory.h>
#include <fcntl.h>

#ifdef SET_UID

#ifndef _MSC_VER
#include <sys/param.h>
#include <sys/file.h>

#ifdef __linux__
#include <sys/file.h>
#endif

#include <pwd.h>

#include <unistd.h>

#else /* _MSC_VER */

#ifndef WINDOWS
#define WINDOWS
#endif

#undef SET_UID

#endif /* _MSC_VER */

#include <sys/stat.h>

#endif /* SET_UID */

#include <string.h>
#include <stdarg.h>

#endif
