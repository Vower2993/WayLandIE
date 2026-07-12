#ifndef __WINE_DEBUG_H
#define __WINE_DEBUG_H
#define WINE_DEFAULT_DEBUG_CHANNEL(ch) static const char __wine_dbch[] = #ch
#define FIXME(...) do {} while(0)
#define ERR(...) do {} while(0)
#define WARN(...) do {} while(0)
#define TRACE(...) do {} while(0)
#endif
