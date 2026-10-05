/*
 * waybar-hover: a waybar CFFI module that gives the audio module a hover hook.
 *
 * Waybar has no on-hover action. This module runs inside waybar, finds the widget of another
 * module (default "pulseaudio"), and reports pointer enter/leave/click on it, with its position,
 * to tandem-mixer over a unix datagram socket:
 *     "enter <x> <width> <bar_width>"   x/width in bar coordinates (logical px)
 *     "leave"
 *     "click <button>"
 *     "hello"                           sent once per waybar (re)start
 *     "layout <left_end> <right_start> <bar_width>"   where the left modules end and the right ones
 *                                       start (bar coordinates), on every change + every 3 s; the
 *                                       now-playing pill sizes itself to fit between them
 * It also shows a small indicator label (its own widget, CSS #tandem) whose text the mixer
 * writes to $XDG_RUNTIME_DIR/tandem-indicator; empty file or no file = hidden.
 *
 * waybar config:
 *   "cffi/tandem": { "module_path": "/home/<you>/.local/lib/tandem/waybar-hover.so",
 *                            "target": "pulseaudio" }
 *
 * No GTK headers needed: the handful of GTK/GLib calls are declared below, and resolve against
 * the GTK that waybar already has loaded. Build: see build.sh.
 */
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

typedef struct _GtkWidget GtkWidget;
typedef struct _GtkContainer GtkContainer;
typedef struct _GList {
  void *data;
  struct _GList *next, *prev;
} GList;
typedef int gboolean;
typedef unsigned long gulong;
typedef size_t GType;
typedef void *gpointer;
typedef void (*GCallback)(void);

extern GtkWidget *gtk_widget_get_toplevel(GtkWidget *);
extern GList *gtk_container_get_children(GtkContainer *);
extern GType gtk_container_get_type(void);
extern gboolean g_type_check_instance_is_a(void *instance, GType type);
extern const char *gtk_widget_get_name(GtkWidget *);
extern GtkWidget *gtk_widget_get_parent(GtkWidget *);
extern gboolean gtk_widget_translate_coordinates(GtkWidget *, GtkWidget *, int, int, int *, int *);
extern int gtk_widget_get_allocated_width(GtkWidget *);
extern GtkWidget *gtk_label_new(const char *);
extern void gtk_label_set_markup(GtkWidget *, const char *);
extern void gtk_widget_set_name(GtkWidget *, const char *);
extern void gtk_widget_show(GtkWidget *);
extern void gtk_widget_hide(GtkWidget *);
extern void gtk_widget_set_no_show_all(GtkWidget *, gboolean);
extern void gtk_container_add(GtkContainer *, GtkWidget *);
extern void g_list_free(GList *);
extern gulong g_signal_connect_data(gpointer, const char *, GCallback, gpointer, void *, int);
extern void g_signal_handler_disconnect(gpointer, gulong);
extern unsigned int g_timeout_add(unsigned int, gboolean (*)(gpointer), gpointer);
extern gboolean g_source_remove(unsigned int);
extern unsigned int g_idle_add(gboolean (*)(gpointer), gpointer);
extern void *gtk_widget_get_style_context(GtkWidget *);
extern gboolean gtk_style_context_has_class(void *, const char *);

/* --- waybar CFFI ABI (resources/custom_modules/cffi_example/waybar_cffi_module.h) --- */
typedef struct wbcffi_module wbcffi_module;
typedef struct {
  wbcffi_module *obj;
  const char *waybar_version;
  GtkContainer *(*get_root_widget)(wbcffi_module *obj);
  void (*queue_update)(wbcffi_module *);
} wbcffi_init_info;
typedef struct {
  const char *key;
  const char *value;
} wbcffi_config_entry;

const size_t wbcffi_version = 2;

typedef struct {
  GtkWidget *root;      /* our container (holds the indicator) */
  GtkWidget *indicator; /* label, CSS #tandem */
  GtkWidget *target;    /* the hooked module's event box */
  gulong h_enter, h_leave, h_press, h_destroy;
  char target_name[64];
  char sock_path[108];
  char ind_path[256];
  char shown[128];
  unsigned int scan_id, ind_id, layout_id, idle_id;
  GtkWidget *left_box, *right_box; /* waybar's .modules-left / .modules-right */
  gulong h_left_alloc, h_right_alloc;
  char last_layout[96];
} Hover;

static void send_msg(Hover *h, const char *msg) {
  int fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
  if (fd < 0) return;
  struct sockaddr_un a = {.sun_family = AF_UNIX};
  strncpy(a.sun_path, h->sock_path, sizeof(a.sun_path) - 1);
  sendto(fd, msg, strlen(msg), MSG_DONTWAIT, (struct sockaddr *)&a, sizeof(a));
  close(fd);
}

static gboolean on_enter(GtkWidget *w, void *ev, gpointer data) {
  Hover *h = data;
  GtkWidget *top = gtk_widget_get_toplevel(w);
  int x = 0, y = 0;
  gtk_widget_translate_coordinates(w, top, 0, 0, &x, &y);
  char msg[96];
  snprintf(msg, sizeof msg, "enter %d %d %d", x, gtk_widget_get_allocated_width(w),
           gtk_widget_get_allocated_width(top));
  send_msg(h, msg);
  return 0; /* let waybar's own handler run too (hover styling) */
}

static gboolean on_leave(GtkWidget *w, void *ev, gpointer data) {
  send_msg(data, "leave");
  return 0;
}

static gboolean on_press(GtkWidget *w, void *ev, gpointer data) {
  /* GdkEventButton: type(int) window(ptr) send_event(int8) time(u32) x y (double) axes(ptr)
     state(u32) button(u32). Read button the portable way would need headers; report 0 = any. */
  send_msg(data, "click 0");
  return 0;
}

static void on_target_destroy(GtkWidget *w, gpointer data) {
  Hover *h = data;
  h->target = NULL;
}

static GtkWidget *find_named(GtkWidget *w, const char *name) {
  const char *n = gtk_widget_get_name(w);
  if (n && strcmp(n, name) == 0) return w;
  if (!g_type_check_instance_is_a(w, gtk_container_get_type())) return NULL;
  GList *kids = gtk_container_get_children((GtkContainer *)w);
  GtkWidget *found = NULL;
  for (GList *l = kids; l && !found; l = l->next) found = find_named(l->data, name);
  g_list_free(kids);
  return found;
}

static GtkWidget *find_class(GtkWidget *w, const char *cls) {
  if (gtk_style_context_has_class(gtk_widget_get_style_context(w), cls)) return w;
  if (!g_type_check_instance_is_a(w, gtk_container_get_type())) return NULL;
  GList *kids = gtk_container_get_children((GtkContainer *)w);
  GtkWidget *found = NULL;
  for (GList *l = kids; l && !found; l = l->next) found = find_class(l->data, cls);
  g_list_free(kids);
  return found;
}

static void send_layout(Hover *h, int force) {
  if (!h->left_box || !h->right_box) return;
  GtkWidget *top = gtk_widget_get_toplevel(h->root);
  int lx = 0, ly = 0, rx = 0, ry = 0;
  gtk_widget_translate_coordinates(h->left_box, top, 0, 0, &lx, &ly);
  gtk_widget_translate_coordinates(h->right_box, top, 0, 0, &rx, &ry);
  char msg[96];
  snprintf(msg, sizeof msg, "layout %d %d %d", lx + gtk_widget_get_allocated_width(h->left_box), rx,
           gtk_widget_get_allocated_width(top));
  if (force || strcmp(msg, h->last_layout) != 0) {
    strcpy(h->last_layout, msg);
    send_msg(h, msg);
  }
}

static gboolean layout_idle(gpointer data) {
  Hover *h = data;
  h->idle_id = 0;
  send_layout(h, 0);
  return 0;
}

/* Drawers opening/closing, modules appearing: report after GTK finishes this allocation. */
static void on_alloc(GtkWidget *w, void *rect, gpointer data) {
  Hover *h = data;
  if (!h->idle_id) h->idle_id = g_idle_add(layout_idle, h);
}

static gboolean layout_tick(gpointer data) { /* the mixer may have (re)started: repeat it */
  send_layout(data, 1);
  return 1;
}

/* Find (and re-find after waybar rebuilds) the target module's event box. */
static gboolean scan(gpointer data) {
  Hover *h = data;
  GtkWidget *top = gtk_widget_get_toplevel(h->root);
  if (!top || top == h->root) return 1;
  if (!h->left_box || !h->right_box) {
    h->left_box = find_class(top, "modules-left");
    h->right_box = find_class(top, "modules-right");
    if (h->left_box && h->right_box) {
      h->h_left_alloc = g_signal_connect_data(h->left_box, "size-allocate", (GCallback)on_alloc, h, NULL, 0);
      h->h_right_alloc = g_signal_connect_data(h->right_box, "size-allocate", (GCallback)on_alloc, h, NULL, 0);
      send_layout(h, 1);
    } else {
      h->left_box = h->right_box = NULL;
    }
  }
  if (h->target) return 1;
  GtkWidget *label = find_named(top, h->target_name);
  if (!label) return 1;
  /* ALabel: event_box_ > label_(name); AIconLabel: event_box_ > box_(name). Hook the event box. */
  GtkWidget *box = gtk_widget_get_parent(label);
  if (!box) return 1;
  h->target = box;
  h->h_enter = g_signal_connect_data(box, "enter-notify-event", (GCallback)on_enter, h, NULL, 0);
  h->h_leave = g_signal_connect_data(box, "leave-notify-event", (GCallback)on_leave, h, NULL, 0);
  h->h_press = g_signal_connect_data(box, "button-press-event", (GCallback)on_press, h, NULL, 0);
  h->h_destroy = g_signal_connect_data(box, "destroy", (GCallback)on_target_destroy, h, NULL, 0);
  return 1;
}

static gboolean poll_indicator(gpointer data) {
  Hover *h = data;
  char buf[128] = "";
  FILE *f = fopen(h->ind_path, "r");
  if (f) {
    size_t n = fread(buf, 1, sizeof buf - 1, f);
    buf[n] = 0;
    fclose(f);
    while (n && (buf[n - 1] == '\n' || buf[n - 1] == ' ')) buf[--n] = 0;
  }
  if (strcmp(buf, h->shown) != 0) {
    strcpy(h->shown, buf);
    if (buf[0]) {
      gtk_label_set_markup(h->indicator, buf);
      gtk_widget_show(h->indicator);
    } else {
      gtk_widget_hide(h->indicator);
    }
  }
  return 1;
}

void *wbcffi_init(const wbcffi_init_info *info, const wbcffi_config_entry *cfg, size_t n) {
  Hover *h = calloc(1, sizeof *h);
  strcpy(h->target_name, "pulseaudio");
  for (size_t i = 0; i < n; i++) {
    /* waybar passes values JSON-encoded: strings arrive quoted */
    if (strcmp(cfg[i].key, "target") == 0) {
      const char *v = cfg[i].value; /* e.g. "\"pulseaudio\"\n" */
      while (*v == ' ' || *v == '"') v++;
      size_t len = strcspn(v, "\"\n");
      if (len >= sizeof h->target_name) len = sizeof h->target_name - 1;
      memcpy(h->target_name, v, len);
      h->target_name[len] = 0;
    }
  }
  const char *rt = getenv("XDG_RUNTIME_DIR");
  if (!rt) rt = "/tmp";
  snprintf(h->sock_path, sizeof h->sock_path, "%s/tandem-mixer.sock", rt);
  snprintf(h->ind_path, sizeof h->ind_path, "%s/tandem-indicator", rt);

  h->root = (GtkWidget *)info->get_root_widget(info->obj);
  h->indicator = gtk_label_new("");
  gtk_widget_set_name(h->indicator, "tandem");
  gtk_widget_set_no_show_all(h->indicator, 1);
  gtk_container_add((GtkContainer *)h->root, h->indicator);

  /* waybar (re)started: overlays on the bar (the now-playing pill) must re-stack above it */
  send_msg(h, "hello");
  h->scan_id = g_timeout_add(500, scan, h);
  h->ind_id = g_timeout_add(1000, poll_indicator, h);
  h->layout_id = g_timeout_add(3000, layout_tick, h);
  return h;
}

void wbcffi_deinit(void *instance) {
  Hover *h = instance;
  g_source_remove(h->scan_id);
  g_source_remove(h->ind_id);
  g_source_remove(h->layout_id);
  if (h->idle_id) g_source_remove(h->idle_id);
  if (h->left_box && h->right_box) {
    g_signal_handler_disconnect(h->left_box, h->h_left_alloc);
    g_signal_handler_disconnect(h->right_box, h->h_right_alloc);
  }
  if (h->target) {
    g_signal_handler_disconnect(h->target, h->h_enter);
    g_signal_handler_disconnect(h->target, h->h_leave);
    g_signal_handler_disconnect(h->target, h->h_press);
    g_signal_handler_disconnect(h->target, h->h_destroy);
  }
  free(h);
}
