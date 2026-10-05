// The hero demo: something leaves the phone, crosses the seam, and shows up on the computer.
(() => {
  const scenes = [
    { app: "Messages", text: "Your verification code is 482913", action: "Code spotted", packet: "482913",
      title: "From your phone", toast: "Code 482913 copied", term: "482913" },
    { app: "Firefox", text: "Copied tandem.aidenwb.com", action: "Copied", packet: "tandem.aidenwb.com",
      title: "From your phone", toast: "tandem.aidenwb.com", term: "xdg-open tandem.aidenwb.com" },
    { app: "Signal · Maya", text: "Running 5 min late, save me a seat", action: "New message", packet: "notification",
      title: "Maya · Signal", toast: "Running 5 min late, save me a seat", term: "" },
    { app: "Screenshot", text: "Screenshot saved", action: "Sending", packet: "Screenshot.png · 2.1 MB",
      title: "Screenshot", toast: "Saved to Pictures/Phone · copied", term: "ls ~/Pictures/Phone" },
  ];
  const hero = document.querySelector(".hero");
  const $ = (id) => document.getElementById(id);
  const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  let i = 0;

  function show(s, sent) {
    $("ph-app").textContent = s.app;
    $("ph-text").textContent = s.text;
    $("ph-action").textContent = s.action;
    $("packet").textContent = s.packet;
    $("toast-title").textContent = s.title;
    $("toast-text").textContent = s.toast;
    $("term-line").textContent = s.term;
    hero.classList.toggle("sent", sent);
  }

  if (reduced) {
    show(scenes[0], true);
    setInterval(() => show(scenes[(i = (i + 1) % scenes.length)], true), 6000);
  } else {
    const run = () => {
      const s = scenes[i];
      hero.classList.remove("sent", "sending");
      show(s, false);
      setTimeout(() => hero.classList.add("sending"), 700);
      setTimeout(() => hero.classList.add("sent"), 1100);
      i = (i + 1) % scenes.length;
    };
    run();
    setInterval(run, 4600);
  }

  document.querySelectorAll("[data-copy]").forEach((b) => {
    b.addEventListener("click", async () => {
      const text = document.getElementById(b.dataset.copy).textContent;
      try {
        await navigator.clipboard.writeText(text);
        b.textContent = "Copied";
      } catch {
        b.textContent = "Select it";
      }
      setTimeout(() => (b.textContent = "Copy"), 1800);
    });
  });
})();
