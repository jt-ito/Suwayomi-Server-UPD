(function () {
  'use strict';

  const ROOT_ROUTES = [
    '/',
    '/library',
    '/updates',
    '/history',
    '/browse',
    '/sources',
    '/downloads',
    '/settings',
    '/tracker',
    '/about',
    '/more'
  ];

  // On mobile, only the bottom-nav destinations are directly reachable — Settings,
  // Downloads, Tracker, and About are pushed from the "More" list instead, so they need
  // their own back arrow to return there (unlike on desktop, where they're direct
  // sidebar items and the back arrow would be redundant).
  const MOBILE_ROOT_ROUTES = ['/', '/library', '/updates', '/history', '/browse', '/more'];

  function isMobileViewport() {
    return !!(window.matchMedia && window.matchMedia('(max-width: 599.98px)').matches);
  }

  function isRootRoute(pathname) {
    const cleanPath = pathname.replace(/\/$/, '') || '/';
    const routes = isMobileViewport() ? MOBILE_ROOT_ROUTES : ROOT_ROUTES;
    return routes.includes(cleanPath);
  }

  function getSectionTitle(pathname) {
    const cleanPath = pathname.replace(/\/$/, '') || '/';
    if (cleanPath === '/' || cleanPath.startsWith('/library')) return 'Library';
    if (cleanPath.startsWith('/updates')) return 'Updates';
    if (cleanPath.startsWith('/history')) return 'History';
    if (cleanPath.startsWith('/browse') || cleanPath.startsWith('/sources')) return 'Browse';
    if (cleanPath.startsWith('/settings')) return 'Settings';
    if (cleanPath.startsWith('/downloads')) return 'Downloads';
    if (cleanPath.startsWith('/tracker')) return 'Trackers';
    if (cleanPath.startsWith('/about')) return 'About';
    if (cleanPath.startsWith('/more')) return 'More';
    return '';
  }

  function updateForkLinks() {
    const forkUrl = 'https://github.com/jt-ito/Suwayomi-Server-UPD';
    try {
      const links = document.querySelectorAll('a[href*="github.com/Suwayomi"], a[href*="github.com/jt-ito"]');
      links.forEach(a => {
        a.href = forkUrl;
        if (a.textContent.includes('Suwayomi/Suwayomi') || a.textContent.includes('github.com/Suwayomi')) {
          a.textContent = forkUrl;
        }
      });

      const secondaries = document.querySelectorAll('.MuiListItemText-secondary, .MuiListItemText-secondary *');
      secondaries.forEach(el => {
        if (el.children.length === 0 && (el.textContent.includes('github.com/Suwayomi/Suwayomi-Server') || el.textContent.includes('github.com/Suwayomi/Suwayomi-WebUI'))) {
          el.textContent = forkUrl;
        }
      });
    } catch (_) {}
  }

  function updateRouteState() {
    const pathname = window.location.pathname;
    const isRoot = isRootRoute(pathname);

    // Toggle back button visibility on top-level root pages
    if (isRoot) {
      document.body.classList.add('suwayomi-hide-back-btn');
    } else {
      document.body.classList.remove('suwayomi-hide-back-btn');
    }

    const cleanPath = pathname.replace(/\/$/, '') || '/';
    const isSettings = cleanPath === '/settings' || cleanPath.startsWith('/settings/');
    const isAbout = cleanPath === '/about' || cleanPath.startsWith('/about');
    const isMore = cleanPath === '/more' || cleanPath.startsWith('/more');
    document.body.classList.toggle('suwayomi-page-settings', isSettings);
    document.body.classList.toggle('suwayomi-page-about', isAbout);
    document.body.classList.toggle('suwayomi-page-more', isMore);

    updateForkLinks();

    // Contextual title fill if empty. The rendered title tag varies (h1/h6/div
    // depending on viewport & Typography variant), so match any heading-like
    // Typography node in the toolbar rather than a single specific tag.
    const titleEl = document.querySelector(
      '.MuiAppBar-root h1.MuiTypography-root, .MuiAppBar-root [class*="MuiTypography-h"]:not(button *)'
    );
    if (titleEl && !titleEl.textContent.trim()) {
      const section = getSectionTitle(pathname);
      if (section) {
        titleEl.textContent = section;
      }
    }
  }

  function getPrefixedStorageItem(key) {
    try {
      const direct = window.localStorage.getItem(key);
      if (direct !== null) return direct;
      for (let i = 0; i < window.localStorage.length; i++) {
        const k = window.localStorage.key(i);
        if (k && (k.endsWith('_' + key) || k === key)) {
          return window.localStorage.getItem(k);
        }
      }
    } catch (_) {}
    return null;
  }

  const THEME_PALETTES = {
    default: {
      primary: '#5b74ef',
      dark: { bg: '#10121d', paper: '#181b2a', text: '#ffffff', secondaryText: 'rgba(255, 255, 255, 0.7)', primary: '#5b74ef' },
      light: { bg: '#f4f5fb', paper: '#ffffff', text: 'rgba(0, 0, 0, 0.87)', secondaryText: 'rgba(0, 0, 0, 0.6)', primary: '#5b74ef' }
    },
    lavender: {
      primary: '#a076fd',
      dark: { bg: '#111129', paper: '#1d193b', text: '#ffffff', secondaryText: 'rgba(255, 255, 255, 0.7)', primary: '#a076fd' },
      light: { bg: '#EDE2FF', paper: '#E4D5F8', text: 'rgba(0, 0, 0, 0.87)', secondaryText: 'rgba(0, 0, 0, 0.6)', primary: '#6D41C8' }
    },
    dune: {
      primary: '#897869',
      dark: { bg: '#171412', paper: '#24201c', text: '#ffffff', secondaryText: 'rgba(255, 255, 255, 0.7)', primary: '#897869' },
      light: { bg: '#faf8f5', paper: '#f5f3f0', text: 'rgba(0, 0, 0, 0.87)', secondaryText: 'rgba(0, 0, 0, 0.6)', primary: '#897869' }
    },
    rosegold: {
      primary: '#E9A7A1',
      dark: { bg: '#181110', paper: '#261b1a', text: '#ffffff', secondaryText: 'rgba(255, 255, 255, 0.7)', primary: '#E9A7A1' },
      light: { bg: '#F3EFEE', paper: '#EAE1E0', text: 'rgba(0, 0, 0, 0.87)', secondaryText: 'rgba(0, 0, 0, 0.6)', primary: '#C07F7A' }
    },
    'forest dew': {
      primary: '#53a584',
      dark: { bg: '#0f1814', paper: '#16241e', text: '#ffffff', secondaryText: 'rgba(255, 255, 255, 0.7)', primary: '#53a584' },
      light: { bg: '#f2f8f4', paper: '#eaf4ee', text: 'rgba(0, 0, 0, 0.87)', secondaryText: 'rgba(0, 0, 0, 0.6)', primary: '#53a584' }
    },
    'mountain sunset': {
      primary: '#c55a77',
      dark: { bg: '#1a0d12', paper: '#28151c', text: '#ffffff', secondaryText: 'rgba(255, 255, 255, 0.7)', primary: '#c55a77' },
      light: { bg: '#f7f3f4', paper: '#e5d6da', text: 'rgba(0, 0, 0, 0.87)', secondaryText: 'rgba(0, 0, 0, 0.6)', primary: '#974258' }
    },
    'montain sunset': {
      primary: '#c55a77',
      dark: { bg: '#1a0d12', paper: '#28151c', text: '#ffffff', secondaryText: 'rgba(255, 255, 255, 0.7)', primary: '#c55a77' },
      light: { bg: '#f7f3f4', paper: '#e5d6da', text: 'rgba(0, 0, 0, 0.87)', secondaryText: 'rgba(0, 0, 0, 0.6)', primary: '#974258' }
    },
    crimson: {
      primary: '#DC143C',
      dark: { bg: '#180a0c', paper: '#281014', text: '#ffffff', secondaryText: 'rgba(255, 255, 255, 0.7)', primary: '#DC143C' },
      light: { bg: '#fdf2f4', paper: '#fae8eb', text: 'rgba(0, 0, 0, 0.87)', secondaryText: 'rgba(0, 0, 0, 0.6)', primary: '#DC143C' }
    },
    'minty miracles': {
      primary: '#5CE6A1',
      dark: { bg: '#0d1a13', paper: '#14261c', text: '#ffffff', secondaryText: 'rgba(255, 255, 255, 0.7)', primary: '#5CE6A1' },
      light: { bg: '#e9f3ee', paper: '#d6eae0', text: 'rgba(0, 0, 0, 0.87)', secondaryText: 'rgba(0, 0, 0, 0.6)', primary: '#00c56a' }
    },
    'orange juice': {
      primary: '#ffb546',
      dark: { bg: '#19130a', paper: '#261e12', text: '#ffffff', secondaryText: 'rgba(255, 255, 255, 0.7)', primary: '#ffb546' },
      light: { bg: '#f5f0e8', paper: '#ede3d3', text: 'rgba(0, 0, 0, 0.87)', secondaryText: 'rgba(0, 0, 0, 0.6)', primary: '#e74c00' }
    },
    'bright pink': {
      primary: '#FF007F',
      dark: { bg: '#190812', paper: '#28101e', text: '#ffffff', secondaryText: 'rgba(255, 255, 255, 0.7)', primary: '#FF007F' },
      light: { bg: '#fdf0f7', paper: '#fae6f0', text: 'rgba(0, 0, 0, 0.87)', secondaryText: 'rgba(0, 0, 0, 0.6)', primary: '#FF007F' }
    },
    veronica: {
      primary: '#A020F0',
      dark: { bg: '#180c1f', paper: '#261332', text: '#ffffff', secondaryText: 'rgba(255, 255, 255, 0.7)', primary: '#A020F0' },
      light: { bg: '#fbf5fd', paper: '#f4e6fa', text: 'rgba(0, 0, 0, 0.87)', secondaryText: 'rgba(0, 0, 0, 0.6)', primary: '#A020F0' }
    },
    'tree frog green': {
      primary: '#8ace31',
      dark: { bg: '#12180c', paper: '#1d2713', text: '#ffffff', secondaryText: 'rgba(255, 255, 255, 0.7)', primary: '#8ace31' },
      light: { bg: '#edf1e6', paper: '#dde6d0', text: 'rgba(0, 0, 0, 0.87)', secondaryText: 'rgba(0, 0, 0, 0.6)', primary: '#4f9513' }
    },
    'ying and yang': {
      primary: '#ffffff',
      dark: { bg: '#000000', paper: '#121212', text: '#ffffff', secondaryText: 'rgba(255, 255, 255, 0.7)', primary: '#ffffff' },
      light: { bg: '#ffffff', paper: '#efefef', text: 'rgba(0, 0, 0, 0.87)', secondaryText: 'rgba(0, 0, 0, 0.6)', primary: '#000000' }
    }
  };

  function isValidColor(c) {
    return !!(c && typeof c === 'string' && c.trim() !== '' && c !== 'transparent' && c !== 'rgba(0, 0, 0, 0)');
  }

  function getActiveThemePalette() {
    let isDark = true;
    try {
      const storedMode = getPrefixedStorageItem('themeMode');
      let mode = storedMode;
      try { mode = JSON.parse(storedMode); } catch (_) {}
      if (mode === 'dark') {
        isDark = true;
      } else if (mode === 'light') {
        isDark = false;
      } else {
        const schemeAttr = document.documentElement.getAttribute('data-mui-color-scheme');
        if (schemeAttr) {
          isDark = schemeAttr !== 'light';
        } else {
          isDark = !window.matchMedia || window.matchMedia('(prefers-color-scheme: dark)').matches;
        }
      }
    } catch (_) {}

    let themeId = 'default';
    try {
      const storedTheme = getPrefixedStorageItem('appTheme');
      if (storedTheme) {
        let parsed = storedTheme;
        try {
          parsed = JSON.parse(storedTheme);
        } catch (_) {}
        if (typeof parsed === 'string') {
          themeId = parsed.toLowerCase().trim();
        } else if (parsed && typeof parsed === 'object') {
          themeId = (parsed.id || parsed.name || 'default').toLowerCase().trim();
        }
      }
    } catch (_) {}

    let isPureBlack = false;
    try {
      const storedBg = getPrefixedStorageItem('theme_background');
      if (storedBg && (storedBg === '"#000000"' || storedBg === '#000000')) {
        isPureBlack = true;
      }
      const storedPureBlack = getPrefixedStorageItem('shouldUsePureBlackMode');
      if (storedPureBlack && (storedPureBlack === 'true' || storedPureBlack === true)) {
        isPureBlack = true;
      }
    } catch (_) {}

    const preset = THEME_PALETTES[themeId] || THEME_PALETTES.default;
    const presetMode = isDark ? preset.dark : preset.light;

    // The fork ships many named themes (Dune, Lavender, Rosegold, ...) that the WebUI's
    // real MUI theme object has no concept of — it only ever knows light/dark. Its
    // --mui-palette-* CSS vars are therefore always defined, but never reflect the
    // theme actually selected here. Trust the resolved preset directly instead of
    // preferring those vars, or every custom theme silently collapses into whatever
    // MUI's generic base theme happens to be (this was the source of custom themes not
    // applying — flat/black app bars, "default"-looking Settings/More pages, etc).
    const bg = (isPureBlack && isDark) ? '#000000' : presetMode.bg;
    const paper = (isPureBlack && isDark) ? '#0c0d12' : presetMode.paper;
    let textColor = presetMode.text;

    // Safety guard against black-text-on-dark or white-text-on-light
    if (isDark && (textColor === 'rgb(0, 0, 0)' || textColor === '#000000' || textColor === '#000')) {
      textColor = '#ffffff';
    } else if (!isDark && (textColor === 'rgb(255, 255, 255)' || textColor === '#ffffff' || textColor === '#fff')) {
      textColor = 'rgba(0, 0, 0, 0.87)';
    }

    const primary = (window.__suwayomiDynamicAccent && isValidColor(window.__suwayomiDynamicAccent))
      ? window.__suwayomiDynamicAccent
      : (presetMode.primary || preset.primary);

    return { bg, paper, textColor, primary, isDark };
  }

  let cachedThemeKey = '';
  function updateThemeColors() {
    const palette = getActiveThemePalette();
    const currentKey = `${palette.bg}|${palette.paper}|${palette.textColor}|${palette.primary}|${palette.isDark}`;
    if (currentKey === cachedThemeKey) return;
    cachedThemeKey = currentKey;

    document.documentElement.style.setProperty('--suwayomi-theme-bg', palette.bg);
    document.documentElement.style.setProperty('--suwayomi-theme-surface', palette.paper);
    document.documentElement.style.setProperty('--suwayomi-theme-paper', palette.paper);
    document.documentElement.style.setProperty('--suwayomi-theme-text', palette.textColor);
    document.documentElement.style.setProperty('--suwayomi-theme-subtext', palette.isDark ? 'rgba(255, 255, 255, 0.65)' : 'rgba(0, 0, 0, 0.6)');
    document.documentElement.style.setProperty('--suwayomi-theme-divider', palette.isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.1)');
    document.documentElement.style.setProperty('--suwayomi-theme-accent', palette.primary);

    document.documentElement.style.setProperty('--suwayomi-webview-bg', palette.bg);
    document.documentElement.style.setProperty('--suwayomi-webview-surface', palette.paper);
    document.documentElement.style.setProperty('--suwayomi-webview-text', palette.textColor);
    document.documentElement.style.setProperty('--suwayomi-webview-accent', palette.primary);

    document.documentElement.style.setProperty('--suwayomi-color-scheme', palette.isDark ? 'dark' : 'light');
    const encodedColor = encodeURIComponent(palette.textColor || (palette.isDark ? '#ffffff' : '#111827'));
    document.documentElement.style.setProperty(
      '--suwayomi-select-arrow',
      'url("data:image/svg+xml,%3Csvg xmlns=\'http://www.w3.org/2000/svg\' width=\'14\' height=\'14\' viewBox=\'0 0 24 24\' fill=\'none\' stroke=\'' + encodedColor + '\' stroke-width=\'2\' stroke-linecap=\'round\' stroke-linejoin=\'round\'%3E%3Cpolyline points=\'6 9 12 15 18 9\'%3E%3C/polyline%3E%3C/svg%3E")'
    );

    if (webviewIframeEl) {
      restyleWebviewIframe(webviewIframeEl);
    }
  }
  window.__suwayomiUpdateTheme = updateThemeColors;

  function handleScroll() {
    const isScrolled = window.scrollY > 8 || document.documentElement.scrollTop > 8;
    document.body.classList.toggle('suwayomi-scrolled', isScrolled);
  }

  function isDrawerCollapsed() {
    try {
      const raw = localStorage.getItem('NavBar::isCollapsed');
      if (raw !== null) {
        const parsed = JSON.parse(raw);
        if (typeof parsed === 'boolean') return parsed;
      }
    } catch (_) {}

    const openBtn = document.querySelector('.MuiAppBar-root button[aria-label="open drawer"]');
    if (openBtn && openBtn.style.display !== 'none' && getComputedStyle(openBtn).display !== 'none') {
      return true;
    }

    return false;
  }

  function toggleDrawer(shouldCollapse) {
    try {
      localStorage.setItem('NavBar::isCollapsed', JSON.stringify(shouldCollapse));
    } catch (_) {}

    // Trigger React's own handlers if native buttons are in the DOM
    if (shouldCollapse) {
      const nativeBtn = document.querySelector('.suwayomi-drawer-header button:not(.suwayomi-drawer-toggle-btn)');
      if (nativeBtn) {
        nativeBtn.click();
      }
    } else {
      const openBtn = document.querySelector('.MuiAppBar-root button[aria-label="open drawer"]');
      if (openBtn) {
        openBtn.click();
      }
    }

    updateDrawerState();
  }

  function updateDrawerState() {
    const isCollapsed = isDrawerCollapsed();
    document.body.classList.toggle('suwayomi-drawer-collapsed', isCollapsed);

    const drawerPaper = document.querySelector('.MuiDrawer-paper');
    if (drawerPaper) {
      drawerPaper.classList.toggle('suwayomi-drawer-collapsed', isCollapsed);
      const drawerRoot = drawerPaper.closest('.MuiDrawer-root');
      if (drawerRoot) {
        drawerRoot.classList.toggle('suwayomi-drawer-collapsed', isCollapsed);
      }
    }

    const brandBadge = document.querySelector('.suwayomi-brand-badge-container');
    if (brandBadge) {
      if (isCollapsed) {
        brandBadge.setAttribute('title', 'Expand sidebar');
        brandBadge.setAttribute('aria-label', 'Expand sidebar');
      } else {
        brandBadge.setAttribute('title', 'Suwayomi Server');
        brandBadge.setAttribute('aria-label', 'Suwayomi Server');
      }
    }
  }

  function injectBrandHeader() {
    updateDrawerState();

    const drawerPaper = document.querySelector('.MuiDrawer-paper');
    if (!drawerPaper) return;

    // Find the header div before the first divider
    const firstDivider = drawerPaper.querySelector('.MuiDivider-root');
    if (!firstDivider) return;

    const headerContainer = firstDivider.previousElementSibling;
    if (!headerContainer || headerContainer.tagName !== 'DIV') return;

    if (!headerContainer.classList.contains('suwayomi-drawer-header')) {
      headerContainer.classList.add('suwayomi-drawer-header');
    }

    // Ensure brand badge container exists
    let brandBadge = headerContainer.querySelector('.suwayomi-brand-badge-container');
    if (!brandBadge) {
      brandBadge = document.createElement('a');
      brandBadge.className = 'suwayomi-brand-badge-container';
      brandBadge.href = '#/';
      brandBadge.innerHTML = `
        <img src="./favicon.svg" alt="Suwayomi" class="suwayomi-brand-logo-img" width="24" height="24" />
        <div class="suwayomi-brand-text">
          <span class="suwayomi-brand-name">Suwayomi</span>
          <span class="suwayomi-brand-pill">Server</span>
        </div>
      `;
      brandBadge.addEventListener('click', (e) => {
        if (isDrawerCollapsed()) {
          e.preventDefault();
          e.stopPropagation();
          toggleDrawer(false);
        }
      });
      headerContainer.insertBefore(brandBadge, headerContainer.firstChild);
    } else {
      if (!brandBadge.dataset.suwayomiClickBound) {
        brandBadge.dataset.suwayomiClickBound = 'true';
        brandBadge.addEventListener('click', (e) => {
          if (isDrawerCollapsed()) {
            e.preventDefault();
            e.stopPropagation();
            toggleDrawer(false);
          }
        });
      }
      if (!brandBadge.querySelector('.suwayomi-brand-logo-img')) {
        brandBadge.innerHTML = `
          <img src="./favicon.svg" alt="Suwayomi" class="suwayomi-brand-logo-img" width="24" height="24" />
          <div class="suwayomi-brand-text">
            <span class="suwayomi-brand-name">Suwayomi</span>
            <span class="suwayomi-brand-pill">Server</span>
          </div>
        `;
      }
    }

    // Ensure toggle button (chevron left arrow) exists in header for collapsing
    let toggleBtn = headerContainer.querySelector('.suwayomi-drawer-toggle-btn');
    if (!toggleBtn) {
      toggleBtn = document.createElement('button');
      toggleBtn.className = 'MuiButtonBase-root MuiIconButton-root suwayomi-drawer-toggle-btn';
      toggleBtn.type = 'button';
      toggleBtn.setAttribute('title', 'Collapse sidebar');
      toggleBtn.setAttribute('aria-label', 'Collapse sidebar');
      toggleBtn.innerHTML = `
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="15 18 9 12 15 6"></polyline>
        </svg>
      `;
      toggleBtn.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        toggleDrawer(true);
      });
      headerContainer.appendChild(toggleBtn);
    }

    // Allow clicking anywhere on headerContainer in collapsed mode to expand
    if (!headerContainer.dataset.suwayomiHeaderBound) {
      headerContainer.dataset.suwayomiHeaderBound = 'true';
      headerContainer.addEventListener('click', (e) => {
        if (isDrawerCollapsed() && !e.target.closest('button')) {
          e.preventDefault();
          e.stopPropagation();
          toggleDrawer(false);
        }
      });
    }
  }

  // Intercept pushState & replaceState for SPA route changes
  const origPush = history.pushState;
  history.pushState = function () {
    const ret = origPush.apply(this, arguments);
    window.dispatchEvent(new Event('locationchange'));
    return ret;
  };

  const origReplace = history.replaceState;
  history.replaceState = function () {
    const ret = origReplace.apply(this, arguments);
    window.dispatchEvent(new Event('locationchange'));
    return ret;
  };

  function decorateSearchInputs() {
    const searchContainers = document.querySelectorAll(
      '.MuiAppBar-root .MuiInput-root, .MuiToolbar-root .MuiInput-root, .MuiAppBar-root .MuiInputBase-root'
    );
    searchContainers.forEach((container) => {
      if (!container.querySelector('.suwayomi-search-icon')) {
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('class', 'suwayomi-search-icon');
        svg.setAttribute('width', '16');
        svg.setAttribute('height', '16');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('fill', 'none');
        svg.setAttribute('stroke', 'currentColor');
        svg.setAttribute('stroke-width', '2');
        svg.setAttribute('stroke-linecap', 'round');
        svg.setAttribute('stroke-linejoin', 'round');
        svg.innerHTML = '<circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line>';
        container.insertBefore(svg, container.firstChild);
      }
    });
  }

  function adjustSourceBrowseSpacing() {
    const pathname = window.location.pathname;
    if (!pathname.includes('/sources') && !pathname.includes('/browse')) return;

    const stickyBars = document.querySelectorAll('div[style*="position: sticky"], div[style*="position:sticky"]');
    stickyBars.forEach(bar => {
      const btn = bar.querySelector('button');
      if (btn && !bar.classList.contains('MuiAppBar-root')) {
        bar.style.marginBottom = '14px';
        bar.style.zIndex = '3';
      }
    });

    const grids = document.querySelectorAll('.MuiGrid-container');
    grids.forEach(grid => {
      if (grid.querySelector('.MuiCard-root, .source-manga-library-state-button')) {
        grid.style.paddingTop = '12px';
      }
    });
  }

  // --- In-Page WebView Modal Management ---
  let webviewModalEl = null;
  let webviewIframeEl = null;
  let webviewUrlChipEl = null;
  let currentWebviewUrl = '';

  function isWebviewUrl(url) {
    if (!url || typeof url !== 'string') return false;
    return url.includes('/webview') || url.includes('api/v1/webview');
  }

  function getCleanDisplayUrl(url) {
    try {
      const parsed = new URL(url, window.location.href);
      let targetParam = parsed.searchParams.get('url');
      if (!targetParam && parsed.hash) {
        targetParam = parsed.hash.replace(/^#\/?/, '');
      }
      if (targetParam) {
        try {
          const targetUrl = new URL(targetParam);
          return targetUrl.hostname + (targetUrl.pathname && targetUrl.pathname !== '/' ? targetUrl.pathname : '');
        } catch (_) {
          return targetParam;
        }
      }
      return parsed.pathname;
    } catch (_) {
      return url;
    }
  }

  function restyleWebviewIframe(iframe) {
    if (!iframe) return;
    try {
      const doc = iframe.contentDocument || iframe.contentWindow?.document;
      if (!doc || !doc.body) return;

      const palette = getActiveThemePalette();

      // Inject or update modern overhaul stylesheet into iframe
      let styleTag = doc.getElementById('suwayomi-webview-injected-style');
      if (!styleTag) {
        styleTag = doc.createElement('style');
        styleTag.id = 'suwayomi-webview-injected-style';
        (doc.head || doc.body).appendChild(styleTag);
      }
      styleTag.textContent = `
        /* Modern Theme-Matching WebView Overhaul */
        * {
          box-sizing: border-box;
        }
        html, body {
          background-color: ${palette.bg} !important;
          color: ${palette.textColor} !important;
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif !important;
          overflow: hidden !important;
          margin: 0 !important;
          padding: 0 !important;
          height: 100% !important;
        }
        header {
          background-color: ${palette.paper} !important;
          border-bottom: 1px solid color-mix(in srgb, ${palette.textColor} 10%, transparent) !important;
          box-shadow: 0 2px 10px rgba(0, 0, 0, 0.25) !important;
          padding: 8px 16px !important;
          display: flex !important;
          flex-direction: column !important;
          gap: 6px !important;
          color: ${palette.textColor} !important;
        }
        header h1, header h1#title, header #title {
          font-size: 0.95rem !important;
          font-weight: 600 !important;
          color: ${palette.textColor} !important;
          letter-spacing: -0.01em !important;
          margin: 0 0 4px 0 !important;
          white-space: nowrap !important;
          overflow: hidden !important;
          text-overflow: ellipsis !important;
        }
        header nav {
          display: flex !important;
          align-items: center !important;
          gap: 12px !important;
          width: 100% !important;
        }
        header form#browseForm, header form {
          display: flex !important;
          align-items: center !important;
          gap: 8px !important;
          flex: 1 !important;
          min-width: 0 !important;
          background: color-mix(in srgb, ${palette.textColor} 6%, transparent) !important;
          border: 1px solid color-mix(in srgb, ${palette.textColor} 14%, transparent) !important;
          border-radius: 8px !important;
          padding: 3px 6px 3px 12px !important;
          transition: all 150ms ease !important;
          box-sizing: border-box !important;
        }
        header form#browseForm:focus-within, header form:focus-within {
          background: color-mix(in srgb, ${palette.textColor} 10%, transparent) !important;
          border-color: ${palette.primary} !important;
          box-shadow: 0 0 0 2px color-mix(in srgb, ${palette.primary} 25%, transparent) !important;
        }
        header input#url, header input[name="url"], header input[type="text"] {
          all: unset !important;
          flex: 1 !important;
          min-width: 0 !important;
          color: ${palette.textColor} !important;
          font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace !important;
          font-size: 12.5px !important;
          padding: 4px 0 !important;
        }
        header input#url::placeholder, header input[name="url"]::placeholder {
          color: color-mix(in srgb, ${palette.textColor} 40%, transparent) !important;
        }
        header button#goButton, header button[type="submit"] {
          all: unset !important;
          background: ${palette.primary} !important;
          color: ${palette.isDark ? '#0a0a0f' : '#ffffff'} !important;
          border-radius: 6px !important;
          width: 28px !important;
          height: 28px !important;
          display: inline-flex !important;
          align-items: center !important;
          justify-content: center !important;
          cursor: pointer !important;
          flex-shrink: 0 !important;
          transition: background-color 150ms ease, transform 100ms ease, filter 150ms ease !important;
        }
        header button#goButton:hover, header button[type="submit"]:hover {
          filter: brightness(1.15) !important;
        }
        header button#goButton:active, header button[type="submit"]:active {
          transform: scale(0.92) !important;
        }
        header button#goButton[disabled], header button[type="submit"][disabled] {
          opacity: 0.5 !important;
          cursor: not-allowed !important;
        }
        header button#goButton .arrow-right, header button[type="submit"] .arrow-right {
          display: none !important;
        }
        header label {
          display: inline-flex !important;
          align-items: center !important;
          gap: 6px !important;
          font-size: 12px !important;
          font-weight: 500 !important;
          color: color-mix(in srgb, ${palette.textColor} 75%, transparent) !important;
          background: color-mix(in srgb, ${palette.textColor} 5%, transparent) !important;
          border: 1px solid color-mix(in srgb, ${palette.textColor} 10%, transparent) !important;
          border-radius: 6px !important;
          padding: 5px 10px !important;
          cursor: pointer !important;
          user-select: none !important;
          white-space: nowrap !important;
          transition: all 150ms ease !important;
        }
        header label:hover {
          background: color-mix(in srgb, ${palette.textColor} 10%, transparent) !important;
          color: ${palette.textColor} !important;
        }
        header label input[type="checkbox"] {
          accent-color: ${palette.primary} !important;
          cursor: pointer !important;
          margin: 0 !important;
        }
        header p {
          margin: 0 !important;
          font-size: 11px !important;
          color: color-mix(in srgb, ${palette.textColor} 50%, transparent) !important;
          font-style: normal !important;
          display: flex !important;
          align-items: center !important;
          gap: 4px !important;
        }
        header p i {
          font-style: normal !important;
        }
        main {
          background: ${palette.bg} !important;
        }
        main .message {
          background: color-mix(in srgb, ${palette.paper} 90%, ${palette.textColor} 10%) !important;
          border: 1px solid color-mix(in srgb, ${palette.textColor} 10%, transparent) !important;
          border-radius: 8px !important;
          color: color-mix(in srgb, ${palette.textColor} 75%, transparent) !important;
          font-style: normal !important;
          font-size: 12px !important;
          margin: 12px auto !important;
        }
        main .message.error {
          background: rgba(239, 68, 68, 0.15) !important;
          border-color: rgba(239, 68, 68, 0.3) !important;
          color: #f87171 !important;
        }
        .copydialog, .logindialog {
          background: rgba(0, 0, 0, 0.75) !important;
          backdrop-filter: blur(8px) !important;
        }
        .copydialog__inner, .logindialog__inner {
          background: ${palette.paper} !important;
          border: 1px solid color-mix(in srgb, ${palette.textColor} 15%, transparent) !important;
          border-radius: 12px !important;
          color: ${palette.textColor} !important;
          box-shadow: 0 16px 40px rgba(0, 0, 0, 0.6) !important;
        }
        .copydialog input, .logindialog form input {
          background: color-mix(in srgb, ${palette.textColor} 6%, transparent) !important;
          border: 1px solid color-mix(in srgb, ${palette.textColor} 18%, transparent) !important;
          border-radius: 6px !important;
          color: ${palette.textColor} !important;
          padding: 8px 12px !important;
        }
      `;

      // Replace ugly arrow-right with modern SVG icon
      const goBtn = doc.getElementById('goButton');
      if (goBtn && !goBtn.querySelector('svg')) {
        goBtn.innerHTML = `
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <line x1="5" y1="12" x2="19" y2="12"></line>
            <polyline points="12 5 19 12 12 19"></polyline>
          </svg>
        `;
      }

      // Sync inner page title to outer modal title chip
      const updateTitle = () => {
        const titleEl = doc.getElementById('title');
        const urlInput = doc.getElementById('url');
        let titleText = titleEl ? titleEl.textContent.trim() : '';
        if (titleText.startsWith('Suwayomi:')) {
          titleText = titleText.replace(/^Suwayomi:\s*/i, '').trim();
        }
        if (webviewUrlChipEl) {
          const displayUrl = urlInput && urlInput.value ? getCleanDisplayUrl(urlInput.value) : '';
          if (titleText && displayUrl && !displayUrl.includes('/webview')) {
            webviewUrlChipEl.textContent = `${titleText} — ${displayUrl}`;
          } else if (titleText) {
            webviewUrlChipEl.textContent = titleText;
          } else if (displayUrl) {
            webviewUrlChipEl.textContent = displayUrl;
          }
        }
      };
      updateTitle();
      const titleEl = doc.getElementById('title');
      if (titleEl && !titleEl.dataset.suwayomiObserved) {
        titleEl.dataset.suwayomiObserved = 'true';
        const titleObs = new MutationObserver(updateTitle);
        titleObs.observe(titleEl, { childList: true, characterData: true, subtree: true });
      }

      const urlInput = doc.getElementById('url');
      if (urlInput && !urlInput.dataset.suwayomiObserved) {
        urlInput.dataset.suwayomiObserved = 'true';
        urlInput.addEventListener('input', updateTitle);
        urlInput.addEventListener('change', updateTitle);
      }

      // Capture-phase throttle for mousemove to prevent WebSocket & CEF paint saturation
      const inputTrap = doc.getElementById('inputtrap');
      if (inputTrap && !inputTrap.dataset.suwayomiThrottled) {
        inputTrap.dataset.suwayomiThrottled = 'true';
        let lastMove = 0;
        inputTrap.addEventListener('mousemove', (e) => {
          const now = performance.now();
          if (now - lastMove < 33) {
            e.stopImmediatePropagation();
          } else {
            lastMove = now;
          }
        }, true);
      }

      // Automatically revoke leaked object URLs to prevent memory buildup and GC freeze
      const win = iframe.contentWindow;
      if (win && !win.__suwayomiPatchedUrl) {
        win.__suwayomiPatchedUrl = true;
        const origCreate = win.URL.createObjectURL;
        const activeUrls = [];
        win.URL.createObjectURL = function (blob) {
          const u = origCreate.call(win.URL, blob);
          activeUrls.push(u);
          if (activeUrls.length > 3) {
            const old = activeUrls.shift();
            try { win.URL.revokeObjectURL(old); } catch (_) {}
          }
          return u;
        };
      }
    } catch (e) {
      // Cross-origin fallback (if any)
    }
  }

  function ensureWebviewModal() {
    if (webviewModalEl) return webviewModalEl;

    webviewModalEl = document.createElement('div');
    webviewModalEl.id = 'suwayomi-webview-modal';
    webviewModalEl.className = 'suwayomi-webview-backdrop';

    webviewModalEl.innerHTML = `
      <div class="suwayomi-webview-window" role="dialog" aria-modal="true" aria-label="Web View">
        <div class="suwayomi-webview-window-bar">
          <div class="suwayomi-webview-traffic-lights">
            <button type="button" class="suwayomi-webview-dot suwayomi-webview-dot-close" title="Close (Esc)" aria-label="Close"></button>
            <button type="button" class="suwayomi-webview-dot suwayomi-webview-dot-minimize" title="Restore" aria-label="Restore"></button>
            <button type="button" class="suwayomi-webview-dot suwayomi-webview-dot-maximize" title="Maximize" aria-label="Maximize"></button>
          </div>
          <div class="suwayomi-webview-title-group">
            <span class="suwayomi-webview-title-badge">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="10"></circle>
                <line x1="2" y1="12" x2="22" y2="12"></line>
                <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path>
              </svg>
              WebView
            </span>
            <span class="suwayomi-webview-url-chip">Loading...</span>
          </div>
          <div class="suwayomi-webview-actions">
            <button type="button" class="suwayomi-webview-btn suwayomi-webview-btn-reload" title="Reload page" aria-label="Reload">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67"/>
              </svg>
            </button>
            <button type="button" class="suwayomi-webview-btn suwayomi-webview-btn-popout" title="Open in new browser tab" aria-label="Open in new tab">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"></path>
                <polyline points="15 3 21 3 21 9"></polyline>
                <line x1="10" y1="14" x2="21" y2="3"></line>
              </svg>
            </button>
            <button type="button" class="suwayomi-webview-btn suwayomi-webview-btn-maximize" title="Toggle maximize" aria-label="Toggle maximize">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"></path>
              </svg>
            </button>
            <button type="button" class="suwayomi-webview-btn suwayomi-webview-btn-close" title="Close (Esc)" aria-label="Close">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="18" y1="6" x2="6" y2="18"></line>
                <line x1="6" y1="6" x2="18" y2="18"></line>
              </svg>
            </button>
          </div>
        </div>
        <div class="suwayomi-webview-frame-container">
          <iframe class="suwayomi-webview-iframe" allow="clipboard-read; clipboard-write;"></iframe>
        </div>
      </div>
    `;

    document.body.appendChild(webviewModalEl);

    webviewIframeEl = webviewModalEl.querySelector('.suwayomi-webview-iframe');
    webviewUrlChipEl = webviewModalEl.querySelector('.suwayomi-webview-url-chip');

    // Attach iframe restyling on load
    webviewIframeEl.addEventListener('load', () => {
      restyleWebviewIframe(webviewIframeEl);
    });

    // Close actions
    webviewModalEl.querySelectorAll('.suwayomi-webview-dot-close, .suwayomi-webview-btn-close').forEach((btn) => {
      btn.addEventListener('click', closeInPageWebview);
    });

    // Minimize / Restore action
    const minBtn = webviewModalEl.querySelector('.suwayomi-webview-dot-minimize');
    if (minBtn) {
      minBtn.addEventListener('click', () => {
        webviewModalEl.classList.remove('suwayomi-maximized');
      });
    }

    // Maximize actions
    webviewModalEl.querySelectorAll('.suwayomi-webview-dot-maximize, .suwayomi-webview-btn-maximize').forEach((btn) => {
      btn.addEventListener('click', () => {
        webviewModalEl.classList.toggle('suwayomi-maximized');
      });
    });

    // Reload action
    const reloadBtn = webviewModalEl.querySelector('.suwayomi-webview-btn-reload');
    if (reloadBtn) {
      reloadBtn.addEventListener('click', () => {
        if (webviewIframeEl && currentWebviewUrl) {
          webviewIframeEl.src = currentWebviewUrl;
        }
      });
    }

    // Popout to new tab
    const popoutBtn = webviewModalEl.querySelector('.suwayomi-webview-btn-popout');
    if (popoutBtn) {
      popoutBtn.addEventListener('click', () => {
        if (currentWebviewUrl) {
          const targetUrl = currentWebviewUrl;
          closeInPageWebview();
          origWindowOpen.call(window, targetUrl, '_blank', 'noopener,noreferrer');
        }
      });
    }

    // Backdrop click outside window
    webviewModalEl.addEventListener('click', (e) => {
      if (e.target === webviewModalEl) {
        closeInPageWebview();
      }
    });

    // Escape key
    window.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && webviewModalEl && webviewModalEl.classList.contains('suwayomi-open')) {
        closeInPageWebview();
      }
    });

    return webviewModalEl;
  }

  function openInPageWebview(url) {
    ensureWebviewModal();
    currentWebviewUrl = url;

    if (webviewUrlChipEl) {
      webviewUrlChipEl.textContent = getCleanDisplayUrl(url);
      webviewUrlChipEl.title = url;
    }

    if (webviewIframeEl) {
      webviewIframeEl.src = url;
    }

    webviewModalEl.classList.add('suwayomi-open');
    document.body.style.overflow = 'hidden';

    // Proactively restyle iframe at multiple ticks as it initializes
    [60, 180, 400, 800, 1500].forEach((ms) => {
      setTimeout(() => restyleWebviewIframe(webviewIframeEl), ms);
    });
  }

  function closeInPageWebview() {
    if (!webviewModalEl) return;
    webviewModalEl.classList.remove('suwayomi-open');
    webviewModalEl.classList.remove('suwayomi-maximized');
    document.body.style.overflow = '';
    setTimeout(() => {
      if (webviewIframeEl && !webviewModalEl.classList.contains('suwayomi-open')) {
        webviewIframeEl.src = 'about:blank';
      }
    }, 250);
  }

  // Intercept window.open
  const origWindowOpen = window.open;
  window.open = function (url, target, features) {
    if (typeof url === 'string' && isWebviewUrl(url)) {
      openInPageWebview(url);
      return null;
    }
    return origWindowOpen.apply(this, arguments);
  };

  // Intercept link clicks with capture phase
  document.addEventListener('click', function (e) {
    const link = e.target.closest('a');
    if (link && link.href && isWebviewUrl(link.href)) {
      e.preventDefault();
      e.stopPropagation();
      openInPageWebview(link.href);
    }
  }, true);

  // --- Multi-User Management & Account Switcher ---
  let cachedCurrentUser = null;
  let cachedTotalAccountsCount = 1;
  let cachedOtherAccounts = [];
  let isFetchingUser = false;
  let userModalEl = null;

  function getStoredToken() {
    try {
      return localStorage.getItem('suwayomi-server-token') || null;
    } catch (_) {
      return null;
    }
  }

  function setStoredToken(token) {
    try {
      if (token) {
        localStorage.setItem('suwayomi-server-token', token);
        document.cookie = 'suwayomi-server-token=' + token + '; path=/; max-age=2592000; SameSite=Lax';
      } else {
        localStorage.removeItem('suwayomi-server-token');
        document.cookie = 'suwayomi-server-token=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT; SameSite=Lax';
      }
    } catch (_) {}
  }

  // Intercept window.fetch to automatically include Bearer token
  const origFetch = window.fetch;
  window.fetch = function (input, init) {
    const token = getStoredToken();
    if (token) {
      init = init || {};
      const headers = new Headers(init.headers || {});
      if (!headers.has('Authorization')) {
        headers.set('Authorization', 'Bearer ' + token);
      }
      init.headers = headers;
    }
    return origFetch.call(this, input, init);
  };

  function escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  const USERNAME_VALIDATION_REGEX = /^[a-zA-Z0-9._-]{3,32}$/;

  function validateAuthInputs(username, password) {
    const u = (username || '').trim();
    if (!u) return 'Username is required.';
    if (u.length < 3 || u.length > 32) {
      return 'Username must be between 3 and 32 characters.';
    }
    if (!USERNAME_VALIDATION_REGEX.test(u)) {
      return 'Username can only contain letters, numbers, underscores, hyphens, and dots (no script tags or special characters).';
    }
    if (/[<>"'/\\`]/.test(u)) {
      return 'Username contains invalid characters.';
    }
    if (!password) return 'Password is required.';
    if (password.length < 4) {
      return 'Password must be at least 4 characters.';
    }
    if (password.length > 128) {
      return 'Password cannot exceed 128 characters.';
    }
    if (/[\x00-\x1F\x7F]/.test(password)) {
      return 'Password contains invalid control characters.';
    }
    return null;
  }

  function cleanAuthErrorMessage(err) {
    const raw = (typeof err === 'string' ? err : (err && err.message) || '').trim();
    if (!raw) return 'Incorrect username or password';
    const lower = raw.toLowerCase();
    if (
      lower.includes('incorrect') ||
      lower.includes('password') ||
      lower.includes('credential') ||
      lower.includes('unauthorized') ||
      raw.includes('graphql.execution') ||
      raw.includes('ExecutionStrategy') ||
      raw.includes('Exception while fetching data')
    ) {
      return 'Incorrect username or password';
    }
    const firstLine = raw.split('\n')[0].replace(/^Exception while fetching data \([^)]+\) :\s*/, '').trim();
    if (firstLine.includes('Exception:')) {
      return 'Incorrect username or password';
    }
    return firstLine || 'Authentication failed';
  }

  let loginPageEl = null;

  function showLoginPage() {
    if (loginPageEl && document.body.contains(loginPageEl)) return;

    loginPageEl = document.createElement('div');
    loginPageEl.className = 'suwayomi-login-page-overlay';
    loginPageEl.innerHTML = `
      <div class="suwayomi-login-card">
        <div class="suwayomi-login-card-brand">
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path>
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path>
          </svg>
          <span class="suwayomi-login-card-brand-title">Suwayomi</span>
        </div>
        <h2 class="suwayomi-login-card-heading">Sign In</h2>
        <p class="suwayomi-login-card-sub">Enter your account credentials to access your manga library</p>
        <form class="suwayomi-login-form">
          <div class="suwayomi-login-error suwayomi-user-error-banner" style="display: none;"></div>
          <div class="suwayomi-user-form-group">
            <label for="suwayomi-login-username">Username</label>
            <input type="text" id="suwayomi-login-username" name="username" placeholder="Username" required pattern="^[a-zA-Z0-9._-]{3,32}$" minlength="3" maxlength="32" title="3-32 characters: letters, numbers, underscores, dots, or hyphens" autocomplete="username" />
          </div>
          <div class="suwayomi-user-form-group">
            <label for="suwayomi-login-password">Password</label>
            <input type="password" id="suwayomi-login-password" name="password" placeholder="Password" required minlength="4" maxlength="128" autocomplete="current-password" />
          </div>
          <button type="submit" class="suwayomi-login-btn-submit">
            Sign In
          </button>
        </form>
      </div>
    `;

    document.body.appendChild(loginPageEl);

    const form = loginPageEl.querySelector('.suwayomi-login-form');
    const errBanner = loginPageEl.querySelector('.suwayomi-login-error');
    const submitBtn = loginPageEl.querySelector('.suwayomi-login-btn-submit');
    const usernameInput = loginPageEl.querySelector('#suwayomi-login-username');
    if (usernameInput) usernameInput.focus();

    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      errBanner.style.display = 'none';

      const username = form.username.value.trim();
      const password = form.password.value;

      const validationErr = validateAuthInputs(username, password);
      if (validationErr) {
        errBanner.textContent = validationErr;
        errBanner.style.display = 'block';
        return;
      }

      submitBtn.disabled = true;
      submitBtn.textContent = 'Authenticating...';

      try {
        const res = await fetch('./api/graphql', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            query: 'mutation($input: LoginInput!) { login(input: $input) { accessToken refreshToken } }',
            variables: { input: { username, password } }
          })
        });
        const data = await res.json();
        if (data.errors && data.errors.length) {
          throw new Error(data.errors[0].message || 'Invalid username or password');
        }
        if (data.data && data.data.login && data.data.login.accessToken) {
          const token = data.data.login.accessToken;
          setStoredToken(token);
          localStorage.removeItem('suwayomi-signed-out');
          hideLoginPage();
          cachedCurrentUser = null;
          await fetchCurrentUser(true);
          injectUserProfile();
          window.location.reload();
        } else {
          throw new Error('Invalid username or password');
        }
      } catch (err) {
        errBanner.textContent = cleanAuthErrorMessage(err);
        errBanner.style.display = 'block';
      } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = 'Sign In';
      }
    });
  }

  function hideLoginPage() {
    if (loginPageEl && loginPageEl.parentNode) {
      loginPageEl.parentNode.removeChild(loginPageEl);
    }
    loginPageEl = null;
  }

  async function fetchCurrentUser(force = false) {
    if (cachedCurrentUser && !force) return cachedCurrentUser;
    if (isFetchingUser) return null;

    const token = getStoredToken();
    const isSignedOut = localStorage.getItem('suwayomi-signed-out') === 'true';

    // If explicitly signed out or no token is saved, do NOT auto-login as Admin!
    if (!token || isSignedOut) {
      cachedCurrentUser = null;
      showLoginPage();
      return null;
    }

    isFetchingUser = true;

    try {
      const headers = { 'Content-Type': 'application/json' };
      if (token) {
        headers['Authorization'] = 'Bearer ' + token;
      }

      const res = await fetch('./api/graphql', {
        method: 'POST',
        headers: headers,
        body: JSON.stringify({
          query: 'query { me { id username role createdAt lastLoginAt } }'
        })
      });

      const json = await res.json();
      if (json.data && json.data.me) {
        cachedCurrentUser = json.data.me;
        hideLoginPage();
      } else {
        setStoredToken(null);
        cachedCurrentUser = null;
        showLoginPage();
      }
    } catch (err) {
      console.warn('Suwayomi: failed to resolve current user', err);
      cachedCurrentUser = null;
      showLoginPage();
    } finally {
      isFetchingUser = false;
    }

    return cachedCurrentUser;
  }

  async function injectUserProfile() {
    const drawerPaper = document.querySelector('.MuiDrawer-paper');
    if (!drawerPaper) return;

    let footer = drawerPaper.querySelector('.suwayomi-drawer-footer');
    if (!footer) {
      footer = document.createElement('div');
      footer.className = 'suwayomi-drawer-footer';
      footer.innerHTML = `
        <button type="button" class="suwayomi-user-profile-card" aria-label="Account switcher">
          <div class="suwayomi-user-avatar">?</div>
          <div class="suwayomi-user-meta">
            <span class="suwayomi-user-name">Loading...</span>
            <div class="suwayomi-user-role-row">
              <span class="suwayomi-user-role-badge">USER</span>
            </div>
          </div>
          <div class="suwayomi-user-switch-icon">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M7 16V4m0 0L3 8m4-4l4 4m6 4v12m0 0l4-4m-4 4l-4-4"/>
            </svg>
          </div>
        </button>
      `;

      footer.querySelector('.suwayomi-user-profile-card').addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        openUserManagerModal();
      });

      drawerPaper.appendChild(footer);
    }

    const user = await fetchCurrentUser();
    if (!user) return;

    const avatarEl = footer.querySelector('.suwayomi-user-avatar');
    const nameEl = footer.querySelector('.suwayomi-user-name');
    const roleEl = footer.querySelector('.suwayomi-user-role-badge');
    const cardEl = footer.querySelector('.suwayomi-user-profile-card');

    if (avatarEl) {
      avatarEl.textContent = (user.username || 'U').charAt(0).toUpperCase();
    }
    if (nameEl) {
      nameEl.textContent = user.username || 'User';
    }
    if (roleEl) {
      roleEl.textContent = user.role || 'MEMBER';
    }
    if (cardEl) {
      cardEl.setAttribute('title', 'Logged in as ' + user.username + ' (' + user.role + ') — Click to switch accounts');
    }
  }

  function ensureUserManagerModal() {
    if (userModalEl) return userModalEl;

    userModalEl = document.createElement('div');
    userModalEl.className = 'suwayomi-user-modal-overlay';
    userModalEl.innerHTML = `
      <div class="suwayomi-user-modal">
        <div class="suwayomi-user-modal-header">
          <div class="suwayomi-user-modal-title">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
              <circle cx="12" cy="7" r="4"></circle>
            </svg>
            <span>Accounts</span>
            <span class="suwayomi-user-modal-badge suwayomi-modal-accounts-count">Accounts</span>
          </div>
          <button type="button" class="suwayomi-user-modal-close" aria-label="Close">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="18" y1="6" x2="6" y2="18"></line>
              <line x1="6" y1="6" x2="18" y2="18"></line>
            </svg>
          </button>
        </div>
        <div class="suwayomi-user-modal-body">
          <div class="suwayomi-user-current-section">
            <div class="suwayomi-user-current-avatar">U</div>
            <div class="suwayomi-user-current-info">
              <div class="suwayomi-user-current-name">User</div>
              <div class="suwayomi-user-current-sub">
                <span class="suwayomi-user-role-badge suwayomi-modal-role">MEMBER</span>
                <span class="suwayomi-modal-userid">User ID: #1</span>
                <span class="suwayomi-modal-total-count" style="font-size: 0.68rem; color: color-mix(in srgb, var(--suwayomi-theme-text, #fff) 55%, transparent);"></span>
              </div>
            </div>
            <button type="button" class="suwayomi-user-btn suwayomi-user-btn-secondary suwayomi-user-signout-btn">
              Sign Out
            </button>
          </div>

          <div class="suwayomi-user-tabs suwayomi-manage-tabs-bar" style="display: none; margin-bottom: 14px;">
            <button type="button" class="suwayomi-user-tab active suwayomi-tab-manage-btn">
              Manage Accounts
            </button>
          </div>

          <!-- Switch Account Section -->
          <div class="suwayomi-user-switch-section">
            <form class="suwayomi-user-switch-form">
              <div class="suwayomi-user-error-banner" style="display: none;"></div>
              <div class="suwayomi-user-form-group">
                <label>Username</label>
                <input type="text" name="username" placeholder="Username" required pattern="^[a-zA-Z0-9._-]{3,32}$" minlength="3" maxlength="32" title="3-32 characters: letters, numbers, underscores, dots, or hyphens" autocomplete="username" />
              </div>
              <div class="suwayomi-user-form-group">
                <label>Password</label>
                <input type="password" name="password" placeholder="Password" required minlength="4" maxlength="128" autocomplete="current-password" />
              </div>
              <button type="submit" class="suwayomi-user-btn suwayomi-user-btn-primary suwayomi-switch-account-btn">
                Switch Account
              </button>
            </form>
          </div>

          <!-- Manage Accounts Section (Admin) -->
          <div class="suwayomi-user-manage-section" style="display: none;">
            <div class="suwayomi-user-list-header">
              <span class="suwayomi-user-list-title">Server Accounts</span>
              <button type="button" class="suwayomi-user-btn suwayomi-user-btn-secondary suwayomi-user-btn-sm suwayomi-toggle-create-btn">
                + New User
              </button>
            </div>

            <!-- Create User Form (hidden by default) -->
            <form class="suwayomi-user-create-form" style="display: none; margin-bottom: 14px; padding: 12px; border-radius: 8px; background: color-mix(in srgb, var(--suwayomi-theme-text, #fff) 4%, transparent); border: 1px solid color-mix(in srgb, var(--suwayomi-theme-text, #fff) 8%, transparent);">
              <div class="suwayomi-user-create-error suwayomi-user-error-banner" style="display: none;"></div>
              <div class="suwayomi-user-form-group">
                <label>New Username</label>
                <input type="text" name="new_username" placeholder="Username (3-32 characters)" required pattern="^[a-zA-Z0-9._-]{3,32}$" minlength="3" maxlength="32" title="3-32 characters: letters, numbers, underscores, dots, or hyphens" autocomplete="off" />
              </div>
              <div class="suwayomi-user-form-group">
                <label>New Password</label>
                <input type="password" name="new_password" placeholder="Password (min 4 characters)" required minlength="4" maxlength="128" autocomplete="off" />
              </div>
              <div class="suwayomi-user-form-group">
                <label>Role</label>
                <select name="new_role">
                  <option value="MEMBER">Member (Personal Library & Progress)</option>
                  <option value="ADMIN">Admin (Full Control)</option>
                </select>
              </div>
              <div style="display: flex; gap: 8px; justify-content: flex-end; margin-top: 10px;">
                <button type="button" class="suwayomi-user-btn suwayomi-user-btn-secondary suwayomi-user-btn-sm suwayomi-cancel-create-btn">Cancel</button>
                <button type="submit" class="suwayomi-user-btn suwayomi-user-btn-primary suwayomi-user-btn-sm">Create Account</button>
              </div>
            </form>

            <div class="suwayomi-user-items-container">
              <div style="text-align: center; padding: 12px; font-size: 0.8rem; color: color-mix(in srgb, var(--suwayomi-theme-text, #fff) 50%, transparent);">Loading accounts...</div>
            </div>
          </div>
        </div>
      </div>
    `;

    document.body.appendChild(userModalEl);

    // Close actions
    userModalEl.querySelector('.suwayomi-user-modal-close').addEventListener('click', closeUserManagerModal);
    userModalEl.addEventListener('click', (e) => {
      if (e.target === userModalEl) closeUserManagerModal();
    });
    window.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && userModalEl.classList.contains('suwayomi-open')) {
        closeUserManagerModal();
      }
    });

    // Toggle Manage Accounts / Switch Account
    const manageBtn = userModalEl.querySelector('.suwayomi-tab-manage-btn');
    const switchSection = userModalEl.querySelector('.suwayomi-user-switch-section');
    const manageSection = userModalEl.querySelector('.suwayomi-user-manage-section');

    manageBtn.addEventListener('click', () => {
      const isManaging = manageSection.style.display !== 'none';
      if (isManaging) {
        manageSection.style.display = 'none';
        switchSection.style.display = 'block';
        manageBtn.textContent = 'Manage Accounts';
      } else {
        manageSection.style.display = 'block';
        switchSection.style.display = 'none';
        manageBtn.textContent = '← Switch Account';
      }
    });

    // Sign out button
    userModalEl.querySelector('.suwayomi-user-signout-btn').addEventListener('click', () => {
      closeUserManagerModal();
      setStoredToken(null);
      localStorage.setItem('suwayomi-signed-out', 'true');
      cachedCurrentUser = null;
      showLoginPage();
    });

    // Switch account form submission
    const switchForm = userModalEl.querySelector('.suwayomi-user-switch-form');
    const switchErr = switchForm.querySelector('.suwayomi-user-error-banner');

    switchForm.addEventListener('submit', async (e) => {
      e.preventDefault();
      switchErr.style.display = 'none';

      const username = switchForm.username.value.trim();
      const password = switchForm.password.value;

      const validationErr = validateAuthInputs(username, password);
      if (validationErr) {
        switchErr.textContent = validationErr;
        switchErr.style.display = 'block';
        return;
      }

      const submitBtn = switchForm.querySelector('button[type="submit"]');
      submitBtn.disabled = true;
      submitBtn.textContent = 'Authenticating...';

      try {
        const res = await fetch('./api/graphql', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            query: 'mutation($input: LoginInput!) { login(input: $input) { accessToken refreshToken } }',
            variables: { input: { username, password } }
          })
        });
        const data = await res.json();
        if (data.errors && data.errors.length) {
          throw new Error(data.errors[0].message || 'Login failed');
        }
        if (data.data && data.data.login && data.data.login.accessToken) {
          setStoredToken(data.data.login.accessToken);
          closeUserManagerModal();
          window.location.reload();
        } else {
          throw new Error('Invalid credentials');
        }
      } catch (err) {
        switchErr.textContent = cleanAuthErrorMessage(err);
        switchErr.style.display = 'block';
      } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = 'Switch Account';
      }
    });

    // Toggle create user form
    const toggleCreateBtn = userModalEl.querySelector('.suwayomi-toggle-create-btn');
    const cancelCreateBtn = userModalEl.querySelector('.suwayomi-cancel-create-btn');
    const createForm = userModalEl.querySelector('.suwayomi-user-create-form');
    const createErr = userModalEl.querySelector('.suwayomi-user-create-error');

    toggleCreateBtn.addEventListener('click', () => {
      createForm.style.display = createForm.style.display === 'none' ? 'block' : 'none';
    });
    cancelCreateBtn.addEventListener('click', () => {
      createForm.style.display = 'none';
    });

    // Create user form submission
    createForm.addEventListener('submit', async (e) => {
      e.preventDefault();
      createErr.style.display = 'none';
      const username = createForm.new_username.value.trim();
      const password = createForm.new_password.value;
      const role = createForm.new_role.value;

      const validationErr = validateAuthInputs(username, password);
      if (validationErr) {
        createErr.textContent = validationErr;
        createErr.style.display = 'block';
        return;
      }

      const submitBtn = createForm.querySelector('button[type="submit"]');
      submitBtn.disabled = true;
      submitBtn.textContent = 'Creating...';

      try {
        const res = await fetch('./api/graphql', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            query: 'mutation($input: CreateUserInput!) { createUser(input: $input) { user { id username role createdAt } } }',
            variables: { input: { username, password, role } }
          })
        });
        const data = await res.json();
        if (data.errors && data.errors.length) {
          throw new Error(data.errors[0].message || 'Failed to create user');
        }
        createForm.reset();
        createForm.style.display = 'none';
        await loadServerAccountsList();
      } catch (err) {
        createErr.textContent = cleanAuthErrorMessage(err);
        createErr.style.display = 'block';
      } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = 'Create Account';
      }
    });

    return userModalEl;
  }

  async function loadServerAccountsList() {
    if (!userModalEl) return;
    const container = userModalEl.querySelector('.suwayomi-user-items-container');
    if (!container) return;

    try {
      const res = await fetch('./api/graphql', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: 'query { users { id username role createdAt lastLoginAt } }'
        })
      });
      const json = await res.json();
      if (json.data && json.data.users) {
        const users = json.data.users;
        const current = cachedCurrentUser || { id: 1 };
        cachedTotalAccountsCount = users.length;
        cachedOtherAccounts = users.filter((u) => u.id !== current.id);

        const countBadge = userModalEl.querySelector('.suwayomi-modal-accounts-count');
        if (countBadge) {
          countBadge.textContent = users.length + (users.length === 1 ? ' Account' : ' Accounts');
        }
        const listTitle = userModalEl.querySelector('.suwayomi-user-list-title');
        if (listTitle) {
          listTitle.textContent = 'Server Accounts (' + users.length + ')';
        }
        const totalCountEl = userModalEl.querySelector('.suwayomi-modal-total-count');
        if (totalCountEl) {
          totalCountEl.textContent = '• ' + users.length + (users.length === 1 ? ' account' : ' accounts');
        }
        const userIdEl = userModalEl.querySelector('.suwayomi-modal-userid');
        if (userIdEl && current) {
          userIdEl.textContent = 'User ID: #' + current.id;
        }

        container.innerHTML = users.map((u) => {
          const safeName = escapeHtml(u.username || 'User');
          const safeInitial = escapeHtml((u.username || 'U').charAt(0).toUpperCase());
          const safeRole = escapeHtml(u.role || 'MEMBER');
          return `
          <div class="suwayomi-user-item-row">
            <div class="suwayomi-user-item-info">
              <div class="suwayomi-user-avatar" style="width: 28px; height: 28px; font-size: 11px;">
                ${safeInitial}
              </div>
              <div style="display: flex; flex-direction: column; gap: 1px;">
                <div style="font-size: 0.84rem; font-weight: 600; display: flex; align-items: center; gap: 6px;">
                  <span>${safeName}</span>
                  ${u.id === current.id ? '<span style="font-size: 0.6rem; color: #10b981; font-weight: 700;">(Active)</span>' : ''}
                </div>
                <div style="font-size: 0.68rem; color: color-mix(in srgb, var(--suwayomi-theme-text, #fff) 55%, transparent);">
                  ${safeRole} • ID #${u.id}
                </div>
              </div>
            </div>
            <div class="suwayomi-user-item-actions">
              ${u.id !== 1 && u.id !== current.id ? `
                <button type="button" class="suwayomi-user-btn suwayomi-user-btn-danger suwayomi-user-delete-btn" data-id="${u.id}" data-name="${safeName}" title="Delete account">
                  Delete
                </button>
              ` : ''}
            </div>
          </div>
        `;
        }).join('');

        // Attach delete listeners
        container.querySelectorAll('.suwayomi-user-delete-btn').forEach((btn) => {
          btn.addEventListener('click', async () => {
            const uid = parseInt(btn.dataset.id, 10);
            const uname = btn.dataset.name;
            if (!confirm('Delete user account "' + uname + '"? Their personal library and reading progress will be permanently removed.')) {
              return;
            }
            btn.disabled = true;
            btn.textContent = '...';
            try {
              const delRes = await fetch('./api/graphql', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  query: 'mutation($input: DeleteUserInput!) { deleteUser(input: $input) { success } }',
                  variables: { input: { id: uid } }
                })
              });
              const delData = await delRes.json();
              if (delData.errors && delData.errors.length) {
                throw new Error(delData.errors[0].message || 'Failed to delete');
              }
              await loadServerAccountsList();
            } catch (err) {
              alert('Failed to delete user: ' + err.message);
              btn.disabled = false;
              btn.textContent = 'Delete';
            }
          });
        });
      } else {
        container.innerHTML = '<div style="padding: 10px; font-size: 0.78rem; text-align: center;">Unable to load user accounts.</div>';
      }
    } catch (err) {
      container.innerHTML = '<div style="padding: 10px; font-size: 0.78rem; text-align: center; color: #ef4444;">Error: ' + err.message + '</div>';
    }
  }

  async function openUserManagerModal() {
    const modal = ensureUserManagerModal();
    const user = await fetchCurrentUser(true);

    if (user) {
      modal.querySelector('.suwayomi-user-current-avatar').textContent = (user.username || 'U').charAt(0).toUpperCase();
      modal.querySelector('.suwayomi-user-current-name').textContent = user.username || 'User';
      modal.querySelector('.suwayomi-modal-role').textContent = user.role || 'MEMBER';
      modal.querySelector('.suwayomi-modal-userid').textContent = 'User ID: #' + user.id;

      // Always reset to Switch Account view on open
      const switchSection = modal.querySelector('.suwayomi-user-switch-section');
      const manageSection = modal.querySelector('.suwayomi-user-manage-section');
      const manageBar = modal.querySelector('.suwayomi-manage-tabs-bar');
      const manageBtn = modal.querySelector('.suwayomi-tab-manage-btn');

      if (switchSection) switchSection.style.display = 'block';
      if (manageSection) manageSection.style.display = 'none';

      if (user.role === 'ADMIN') {
        if (manageBar) manageBar.style.display = 'flex';
        if (manageBtn) {
          manageBtn.style.display = 'inline-flex';
          manageBtn.textContent = 'Manage Accounts';
        }
        await loadServerAccountsList();
      } else {
        if (manageBar) manageBar.style.display = 'none';
        if (manageBtn) manageBtn.style.display = 'none';
      }
    }

    modal.classList.add('suwayomi-open');
  }



  function closeUserManagerModal() {
    if (!userModalEl) return;
    userModalEl.classList.remove('suwayomi-open');
  }

  window.addEventListener('popstate', () => window.dispatchEvent(new Event('locationchange')));
  window.addEventListener('locationchange', () => {
    updateRouteState();
    const isManga = window.location.pathname.includes('/manga') || window.location.hash.includes('/manga');
    if (!isManga) {
      window.__suwayomiDynamicAccent = null;
      updateThemeColors();
    } else {
      requestAnimationFrame(() => updateThemeColors());
    }
  });
  window.addEventListener('scroll', handleScroll, { passive: true });
  window.addEventListener('storage', (e) => {
    if (e.key === 'NavBar::isCollapsed') {
      updateDrawerState();
    } else if (e.key === 'theme_background' || e.key === 'appTheme') {
      updateThemeColors();
    }
  });

  // --- Library Multi-Category Search & Filter Engine ---
  let catFilterBarEl = null;
  let catFilterResultsEl = null;
  let catFilterCategories = [];
  let catFilterMangas = [];
  let catFilterMode = 'ALL'; // 'ALL', 'OR', 'AND' — category match mode
  let catFilterSelectedIds = new Set();
  // Tags/genres (this also covers "manga type" — Manhwa/Manhua/Novel/etc. aren't a
  // separate field in the data model, they're just genre strings from the source).
  let catFilterTagMode = 'ALL'; // 'ALL', 'OR', 'AND' — tag match mode
  let catFilterSelectedTags = new Set();
  let catFilterSearchTerm = '';
  let catFilterIsLoading = false;
  let catFilterLastFetchTime = 0;
  let catFilterIsExpanded = true;
  let catFilterHasRendered = false;

  function isLibraryRoute() {
    const p = window.location.pathname.replace(/\/$/, '') || '/';
    return p === '' || p === '/' || p.startsWith('/library');
  }

  function getLibraryContentContainer() {
    return document.getElementById('appMainContainer') || document.querySelector('main') || document.querySelector('[role="main"]');
  }

  // The native "Filter, Sort, Display" bottom-sheet popup (opened via the library
  // toolbar's filter icon) always renders its 3 tab panes in order [filter, sort, display].
  // We embed the category filter controls as a section inside the first (Filter) pane
  // instead of a separate floating bar, so it lives in the popup the user already knows.
  function getOptionsPanelFilterPane() {
    // The anchor class lives on the Drawer root (MuiDrawer-anchorBottom) in this MUI
    // version, not on the paper (MuiDrawer-paperAnchorBottom) — match both to be safe
    // across versions. anchorBottom also correctly excludes the docked sidebar (anchorLeft).
    const panes = document.querySelectorAll(
      '.MuiDrawer-anchorBottom [role="tabpanel"], .MuiDrawer-paperAnchorBottom [role="tabpanel"]'
    );
    return panes.length ? panes[0] : null;
  }

  function getMangaCategoryIds(m) {
    if (m.categories && Array.isArray(m.categories.nodes) && m.categories.nodes.length > 0) {
      return new Set(m.categories.nodes.map(c => c.id));
    }
    return new Set([0]); // Default category is 0 in Suwayomi
  }

  function getMangaTags(m) {
    return new Set(Array.isArray(m.genre) ? m.genre.filter(Boolean).map(g => g.toLowerCase()) : []);
  }

  function getAllLibraryTags() {
    const set = new Set();
    catFilterMangas.forEach(m => {
      if (Array.isArray(m.genre)) {
        m.genre.forEach(g => { if (g) set.add(g); });
      }
    });
    return [...set].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }));
  }

  function getFilterState() {
    const hasCategoryFilter = catFilterMode !== 'ALL' && catFilterSelectedIds.size > 0;
    const hasTagFilter = catFilterTagMode !== 'ALL' && catFilterSelectedTags.size > 0;
    const hasSearchFilter = catFilterSearchTerm.length > 0;
    return {
      hasCategoryFilter,
      hasTagFilter,
      hasSearchFilter,
      isFilterActive: hasCategoryFilter || hasTagFilter || hasSearchFilter
    };
  }

  function getFilterSummaryText() {
    const { hasCategoryFilter, hasTagFilter, hasSearchFilter } = getFilterState();
    const parts = [];
    if (hasCategoryFilter) {
      const selNames = catFilterCategories.filter(c => catFilterSelectedIds.has(c.id)).map(c => c.name);
      parts.push(selNames.join(` ${catFilterMode} `));
    }
    if (hasTagFilter) {
      parts.push([...catFilterSelectedTags].join(` ${catFilterTagMode} `));
    }
    if (hasSearchFilter) {
      parts.push(`"${catFilterSearchTerm}"`);
    }
    if (!parts.length) {
      return `${catFilterMangas.length} titles in library`;
    }
    return parts.join(' • ');
  }

  async function fetchLibraryFilterData(force = false) {
    const now = Date.now();
    // Cache for 30 seconds unless forced, or if already fetched at least once
    if (!force && catFilterLastFetchTime > 0 && (now - catFilterLastFetchTime) < 30000) {
      return false;
    }
    if (catFilterIsLoading) return false;
    catFilterIsLoading = true;
    try {
      const headers = { 'Content-Type': 'application/json' };
      const token = getStoredToken();
      if (token) headers['Authorization'] = 'Bearer ' + token;

      const prevCatJson = JSON.stringify(catFilterCategories);
      const prevMangaCount = catFilterMangas.length;

      // 1. Fetch Categories
      const catRes = await fetch('./api/graphql', {
        method: 'POST',
        headers,
        body: JSON.stringify({
          query: 'query { categories { nodes { id name order mangas { totalCount } } } }'
        })
      });
      const catData = await catRes.json();
      if (catData.data && catData.data.categories && catData.data.categories.nodes) {
        let fetched = catData.data.categories.nodes;
        // Keep only custom categories created by the user (id != 0)
        catFilterCategories = fetched.filter(c => c.id !== 0).sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
      }

      // 2. Fetch User's Library Mangas with categories
      const mangaRes = await fetch('./api/graphql', {
        method: 'POST',
        headers,
        body: JSON.stringify({
          query: 'query { mangas(condition: { inLibrary: true }) { nodes { id title thumbnailUrl artist author genre inLibrary categories { nodes { id name } } } } }'
        })
      });
      const mangaData = await mangaRes.json();
      if (mangaData.data && mangaData.data.mangas && mangaData.data.mangas.nodes) {
        catFilterMangas = mangaData.data.mangas.nodes;
      }
      catFilterLastFetchTime = Date.now();

      const newCatJson = JSON.stringify(catFilterCategories);
      const newMangaCount = catFilterMangas.length;
      return (prevCatJson !== newCatJson || prevMangaCount !== newMangaCount);
    } catch (err) {
      console.warn('Suwayomi: failed to fetch library filter data', err);
      return false;
    } finally {
      catFilterIsLoading = false;
    }
  }

  function cleanupCategoryFilter() {
    const main = getLibraryContentContainer();
    if (main) {
      const nativeChildren = Array.from(main.children).filter(el =>
        el !== catFilterBarEl && el !== catFilterResultsEl
      );
      nativeChildren.forEach(el => {
        if (el.dataset.origDisplay !== undefined) {
          el.style.display = el.dataset.origDisplay;
          delete el.dataset.origDisplay;
        }
      });
    }
    if (catFilterBarEl && catFilterBarEl.parentNode) {
      catFilterBarEl.parentNode.removeChild(catFilterBarEl);
    }
    if (catFilterResultsEl && catFilterResultsEl.parentNode) {
      catFilterResultsEl.parentNode.removeChild(catFilterResultsEl);
    }
    catFilterBarEl = null;
    catFilterResultsEl = null;
    catFilterHasRendered = false;
  }

  function updateCategoryFilterHeader() {
    if (!catFilterBarEl) return;
    const summaryEl = catFilterBarEl.querySelector('.suwayomi-catfilter-summary-text');
    const resetBtn = catFilterBarEl.querySelector('.suwayomi-catfilter-reset-btn');
    const { isFilterActive } = getFilterState();

    if (summaryEl) {
      summaryEl.textContent = getFilterSummaryText();
    }

    if (resetBtn) {
      resetBtn.style.display = isFilterActive ? 'inline-flex' : 'none';
    }
  }

  function buildCategoryFilterHTML() {
    if (!catFilterBarEl) return;

    const prevInput = catFilterBarEl.querySelector('.suwayomi-catfilter-input');
    const wasFocused = prevInput && document.activeElement === prevInput;
    const selStart = prevInput ? prevInput.selectionStart : null;
    const selEnd = prevInput ? prevInput.selectionEnd : null;

    const hasCustomCats = catFilterCategories.length > 0;
    const allTags = getAllLibraryTags();
    const hasTags = allTags.length > 0;
    const { isFilterActive } = getFilterState();
    const shouldExpand = catFilterIsExpanded || isFilterActive;

    // Build category chips if custom categories exist
    let chipsHtml = '';
    if (hasCustomCats) {
      const displayCategories = [...catFilterCategories];
      // If some titles are uncategorized, add Default chip
      const uncategorizedCount = catFilterMangas.filter(m => getMangaCategoryIds(m).has(0)).length;
      if (uncategorizedCount > 0 && !displayCategories.some(c => c.id === 0)) {
        displayCategories.push({ id: 0, name: 'Default', order: 9999 });
      }

      chipsHtml = displayCategories.map(cat => {
        const isSelected = catFilterSelectedIds.has(cat.id);
        const count = catFilterMangas.filter(m => getMangaCategoryIds(m).has(cat.id)).length;
        return `
          <button type="button" class="suwayomi-catfilter-chip ${isSelected ? 'active' : ''}" data-cat-id="${cat.id}">
            <span class="suwayomi-catfilter-chip-name">${escapeHtml(cat.name)}</span>
            <span class="suwayomi-catfilter-chip-count">${count}</span>
          </button>
        `;
      }).join('');
    }

    // Build tag/genre chips (this also covers "manga type" — Manhwa/Manhua/Novel/etc.
    // are just genre strings from the source, not a separate field)
    let tagChipsHtml = '';
    if (hasTags) {
      tagChipsHtml = allTags.map(tag => {
        const isSelected = catFilterSelectedTags.has(tag);
        const count = catFilterMangas.filter(m => getMangaTags(m).has(tag.toLowerCase())).length;
        return `
          <button type="button" class="suwayomi-catfilter-chip suwayomi-catfilter-tag-chip ${isSelected ? 'active' : ''}" data-tag="${escapeHtml(tag)}">
            <span class="suwayomi-catfilter-chip-name">${escapeHtml(tag)}</span>
            <span class="suwayomi-catfilter-chip-count">${count}</span>
          </button>
        `;
      }).join('');
    }

    const summaryText = getFilterSummaryText();

    catFilterBarEl.innerHTML = `
      <div class="suwayomi-catfilter-header">
        <div class="suwayomi-catfilter-title-wrap">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"></polygon>
          </svg>
          <span>Category &amp; Tag Filter</span>
          <span class="suwayomi-catfilter-summary-text">${summaryText}</span>
        </div>

        <div class="suwayomi-catfilter-header-actions">
          <button type="button" class="suwayomi-catfilter-reset-btn" title="Reset filters" style="${isFilterActive ? 'display: inline-flex;' : 'display: none;'}">Reset</button>
          <button type="button" class="suwayomi-catfilter-toggle-btn" title="${shouldExpand ? 'Collapse filter bar' : 'Expand filter bar'}">
            ${shouldExpand ? '▲' : '▼'}
          </button>
        </div>
      </div>

      <div class="suwayomi-catfilter-body ${shouldExpand ? '' : 'collapsed'}">
        <div class="suwayomi-catfilter-search-row">
          <div class="suwayomi-catfilter-search-box">
            <div class="suwayomi-catfilter-search-icon">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="11" cy="11" r="8"></circle>
                <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
              </svg>
            </div>
            <input type="text" class="suwayomi-catfilter-input" placeholder="Search library titles (title, author, genre)..." value="${escapeHtml(catFilterSearchTerm)}" />
            <button type="button" class="suwayomi-catfilter-clear-input" title="Clear search" style="${catFilterSearchTerm ? 'display: flex;' : 'display: none;'}">✕</button>
          </div>
        </div>

        <div class="suwayomi-catfilter-group">
          <div class="suwayomi-catfilter-group-header">
            <span class="suwayomi-catfilter-group-title">Categories</span>
            ${hasCustomCats ? `
              <div class="suwayomi-catfilter-mode-wrap">
                <button type="button" class="suwayomi-catfilter-mode-btn ${catFilterMode === 'ALL' ? 'active' : ''}" data-mode="ALL" title="Show all categories">All</button>
                <button type="button" class="suwayomi-catfilter-mode-btn ${catFilterMode === 'OR' ? 'active' : ''}" data-mode="OR" title="Match any selected category (OR)">Any (OR)</button>
                <button type="button" class="suwayomi-catfilter-mode-btn ${catFilterMode === 'AND' ? 'active' : ''}" data-mode="AND" title="Match all selected categories (AND)">All (AND)</button>
              </div>
            ` : ''}
          </div>
          <div class="suwayomi-catfilter-chips-row">
            ${hasCustomCats
              ? (chipsHtml || '<span style="font-size: 0.74rem; opacity: 0.5;">Loading categories...</span>')
              : '<span style="font-size: 0.74rem; opacity: 0.65;">Create categories in Suwayomi to filter by them here.</span>'
            }
          </div>
        </div>

        <div class="suwayomi-catfilter-group">
          <div class="suwayomi-catfilter-group-header">
            <span class="suwayomi-catfilter-group-title">Tags / Type</span>
            ${hasTags ? `
              <div class="suwayomi-catfilter-mode-wrap">
                <button type="button" class="suwayomi-catfilter-tagmode-btn ${catFilterTagMode === 'ALL' ? 'active' : ''}" data-tagmode="ALL" title="Show all tags">All</button>
                <button type="button" class="suwayomi-catfilter-tagmode-btn ${catFilterTagMode === 'OR' ? 'active' : ''}" data-tagmode="OR" title="Match any selected tag (OR)">Any (OR)</button>
                <button type="button" class="suwayomi-catfilter-tagmode-btn ${catFilterTagMode === 'AND' ? 'active' : ''}" data-tagmode="AND" title="Match all selected tags (AND)">All (AND)</button>
              </div>
            ` : ''}
          </div>
          <div class="suwayomi-catfilter-chips-row suwayomi-catfilter-tags-row">
            ${hasTags
              ? tagChipsHtml
              : '<span style="font-size: 0.74rem; opacity: 0.5;">No tags found in your library yet.</span>'
            }
          </div>
        </div>

        <div class="suwayomi-catfilter-status-bar">
          <span class="suwayomi-catfilter-status-text">Ready</span>
        </div>
      </div>
    `;

    attachCategoryFilterEvents();

    if (wasFocused) {
      const nextInput = catFilterBarEl.querySelector('.suwayomi-catfilter-input');
      if (nextInput) {
        nextInput.focus();
        if (selStart !== null && selEnd !== null) {
          try { nextInput.setSelectionRange(selStart, selEnd); } catch (_) {}
        }
      }
    }
  }

  function attachCategoryFilterEvents() {
    if (!catFilterBarEl) return;

    const toggleBtn = catFilterBarEl.querySelector('.suwayomi-catfilter-toggle-btn');
    if (toggleBtn) {
      toggleBtn.addEventListener('click', () => {
        catFilterIsExpanded = !catFilterIsExpanded;
        buildCategoryFilterHTML();
        applyCategoryFilters();
      });
    }

    const searchInput = catFilterBarEl.querySelector('.suwayomi-catfilter-input');
    const clearBtn = catFilterBarEl.querySelector('.suwayomi-catfilter-clear-input');
    if (searchInput) {
      searchInput.addEventListener('input', (e) => {
        catFilterSearchTerm = e.target.value.trim().toLowerCase();
        if (clearBtn) clearBtn.style.display = catFilterSearchTerm ? 'flex' : 'none';
        applyCategoryFilters();
      });
    }

    if (clearBtn) {
      clearBtn.addEventListener('click', () => {
        catFilterSearchTerm = '';
        if (searchInput) {
          searchInput.value = '';
          searchInput.focus();
        }
        clearBtn.style.display = 'none';
        applyCategoryFilters();
      });
    }

    catFilterBarEl.querySelectorAll('.suwayomi-catfilter-mode-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const mode = btn.dataset.mode;
        catFilterMode = mode;
        if (mode === 'ALL') {
          catFilterSelectedIds.clear();
        } else if (catFilterSelectedIds.size === 0 && catFilterCategories.length > 0) {
          catFilterCategories.forEach(c => catFilterSelectedIds.add(c.id));
        }
        buildCategoryFilterHTML();
        applyCategoryFilters();
      });
    });

    catFilterBarEl.querySelectorAll('.suwayomi-catfilter-chip:not(.suwayomi-catfilter-tag-chip)').forEach(btn => {
      btn.addEventListener('click', () => {
        const id = parseInt(btn.dataset.catId, 10);
        if (catFilterMode === 'ALL') {
          catFilterMode = 'OR';
          catFilterSelectedIds.clear();
          catFilterSelectedIds.add(id);
        } else {
          if (catFilterSelectedIds.has(id)) {
            catFilterSelectedIds.delete(id);
            if (catFilterSelectedIds.size === 0) {
              catFilterMode = 'ALL';
            }
          } else {
            catFilterSelectedIds.add(id);
          }
        }
        buildCategoryFilterHTML();
        applyCategoryFilters();
      });
    });

    catFilterBarEl.querySelectorAll('.suwayomi-catfilter-tagmode-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const mode = btn.dataset.tagmode;
        catFilterTagMode = mode;
        if (mode === 'ALL') {
          catFilterSelectedTags.clear();
        } else if (catFilterSelectedTags.size === 0) {
          getAllLibraryTags().forEach(tag => catFilterSelectedTags.add(tag));
        }
        buildCategoryFilterHTML();
        applyCategoryFilters();
      });
    });

    catFilterBarEl.querySelectorAll('.suwayomi-catfilter-tag-chip').forEach(btn => {
      btn.addEventListener('click', () => {
        const tag = btn.dataset.tag;
        if (catFilterTagMode === 'ALL') {
          catFilterTagMode = 'OR';
          catFilterSelectedTags.clear();
          catFilterSelectedTags.add(tag);
        } else {
          if (catFilterSelectedTags.has(tag)) {
            catFilterSelectedTags.delete(tag);
            if (catFilterSelectedTags.size === 0) {
              catFilterTagMode = 'ALL';
            }
          } else {
            catFilterSelectedTags.add(tag);
          }
        }
        buildCategoryFilterHTML();
        applyCategoryFilters();
      });
    });

    const resetBtn = catFilterBarEl.querySelector('.suwayomi-catfilter-reset-btn');
    if (resetBtn) {
      resetBtn.addEventListener('click', () => {
        catFilterMode = 'ALL';
        catFilterSelectedIds.clear();
        catFilterTagMode = 'ALL';
        catFilterSelectedTags.clear();
        catFilterSearchTerm = '';
        buildCategoryFilterHTML();
        applyCategoryFilters();
      });
    }
  }

  function applyCategoryFilters() {
    if (!catFilterBarEl || !catFilterResultsEl) return;
    const main = getLibraryContentContainer();
    if (!main) return;

    const { hasCategoryFilter, hasTagFilter, isFilterActive } = getFilterState();

    const statusTextEl = catFilterBarEl.querySelector('.suwayomi-catfilter-status-text');

    const nativeChildren = Array.from(main.children).filter(el =>
      el !== catFilterBarEl && el !== catFilterResultsEl
    );

    updateCategoryFilterHeader();

    if (!isFilterActive) {
      catFilterResultsEl.classList.remove('active');
      catFilterResultsEl.style.setProperty('display', 'none', 'important');
      catFilterResultsEl.innerHTML = '';
      nativeChildren.forEach(el => {
        if (el.dataset.origDisplay !== undefined) {
          el.style.display = el.dataset.origDisplay;
          delete el.dataset.origDisplay;
        }
      });
      if (statusTextEl) {
        const hasFilterOptions = catFilterCategories.length > 0 || getAllLibraryTags().length > 0;
        statusTextEl.textContent = hasFilterOptions
          ? `Showing all ${catFilterMangas.length} titles in library. Select categories or tags above to combine with OR / AND.`
          : `Showing all ${catFilterMangas.length} titles in library.`;
      }
      return;
    }

    // Filter active!
    catFilterResultsEl.classList.add('active');
    catFilterResultsEl.style.setProperty('display', 'grid', 'important');

    nativeChildren.forEach(el => {
      if (el.id && el.id.includes('DndDescribedBy')) return;
      if (el.dataset.origDisplay === undefined) {
        el.dataset.origDisplay = el.style.display || '';
      }
      el.style.display = 'none';
    });

    const matched = catFilterMangas.filter(m => {
      if (catFilterSearchTerm) {
        const titleMatch = (m.title || '').toLowerCase().includes(catFilterSearchTerm);
        const artistMatch = (m.artist || '').toLowerCase().includes(catFilterSearchTerm);
        const authorMatch = (m.author || '').toLowerCase().includes(catFilterSearchTerm);
        const genreMatch = Array.isArray(m.genre) && m.genre.some(g => (g || '').toLowerCase().includes(catFilterSearchTerm));
        if (!titleMatch && !artistMatch && !authorMatch && !genreMatch) return false;
      }

      if (hasCategoryFilter) {
        const mCatIds = getMangaCategoryIds(m);
        if (catFilterMode === 'OR') {
          if (![...catFilterSelectedIds].some(id => mCatIds.has(id))) return false;
        } else if (catFilterMode === 'AND') {
          if (![...catFilterSelectedIds].every(id => mCatIds.has(id))) return false;
        }
      }

      if (hasTagFilter) {
        const mTags = getMangaTags(m);
        const selTags = [...catFilterSelectedTags].map(t => t.toLowerCase());
        if (catFilterTagMode === 'OR') {
          if (!selTags.some(t => mTags.has(t))) return false;
        } else if (catFilterTagMode === 'AND') {
          if (!selTags.every(t => mTags.has(t))) return false;
        }
      }

      return true;
    });

    if (statusTextEl) {
      statusTextEl.textContent = `Found ${matched.length} of ${catFilterMangas.length} titles matching ${getFilterSummaryText()}`;
    }

    if (matched.length === 0) {
      catFilterResultsEl.innerHTML = `
        <div class="suwayomi-catfilter-empty">
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="11" cy="11" r="8"></circle>
            <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
          </svg>
          <div style="font-weight: 600; font-size: 0.95rem;">No library items match this filter</div>
          <div style="font-size: 0.8rem; max-width: 320px; line-height: 1.4; opacity: 0.7;">Try clearing the search term or selecting different categories/tags.</div>
        </div>
      `;
      return;
    }

    catFilterResultsEl.innerHTML = matched.map(m => {
      const coverUrl = m.thumbnailUrl || (m.id ? `./api/v1/manga/${m.id}/thumbnail` : '');
      const safeTitle = escapeHtml(m.title || 'Untitled');
      const safeAuthor = escapeHtml(m.author || m.artist || '');
      const mCatNodes = (m.categories && m.categories.nodes && m.categories.nodes.length > 0)
        ? m.categories.nodes
        : [{ id: 0, name: 'Default' }];
      const catBadges = mCatNodes.map(c => `
        <span class="suwayomi-catfilter-card-cat-badge">${escapeHtml(c.name)}</span>
      `).join('');

      return `
        <a href="./manga/${m.id}" class="suwayomi-catfilter-card-item">
          <div class="suwayomi-catfilter-card-cover-wrap">
            ${coverUrl ? `<img src="${coverUrl}" alt="${safeTitle}" class="suwayomi-catfilter-card-cover" loading="lazy" />` : '<div class="suwayomi-catfilter-card-cover" style="display:flex;align-items:center;justify-content:center;color:#666;font-size:0.75rem;">No Cover</div>'}
            <div class="suwayomi-catfilter-card-badges">
              ${catBadges}
            </div>
            <div class="suwayomi-catfilter-card-overlay">
              <div class="suwayomi-catfilter-card-title" title="${safeTitle}">${safeTitle}</div>
              ${safeAuthor ? `<div class="suwayomi-catfilter-card-sub" title="${safeAuthor}">${safeAuthor}</div>` : ''}
            </div>
          </div>
        </a>
      `;
    }).join('');
  }

  async function updateLibraryCategoryFilter() {
    if (!isLibraryRoute()) {
      cleanupCategoryFilter();
      return;
    }

    const main = getLibraryContentContainer();
    if (!main) return;

    const dataChanged = await fetchLibraryFilterData();

    // The results grid (which replaces the native manga cards while a filter is
    // active) always lives in the library content area, regardless of whether the
    // Filter/Sort/Display popup is currently open.
    if (!catFilterResultsEl || !main.contains(catFilterResultsEl)) {
      if (!catFilterResultsEl) {
        catFilterResultsEl = document.createElement('div');
        catFilterResultsEl.className = 'suwayomi-catfilter-results-grid';
      }
      if (main.firstChild) {
        main.insertBefore(catFilterResultsEl, main.firstChild);
      } else {
        main.appendChild(catFilterResultsEl);
      }
      applyCategoryFilters();
      catFilterHasRendered = true;
    } else if (dataChanged) {
      applyCategoryFilters();
    }

    // The controls (category chips, ALL/OR/AND mode, search box) only render while
    // the native popup is open — embedded as a section inside its "Filter" tab.
    const filterPane = getOptionsPanelFilterPane();
    if (filterPane) {
      if (!catFilterBarEl) {
        catFilterBarEl = document.createElement('div');
        catFilterBarEl.className = 'suwayomi-library-filter-bar';
      }
      if (!filterPane.contains(catFilterBarEl)) {
        // Always first: React re-appends its own tab content after switching tabs, so
        // appending here left the bar at the bottom initially but at the top afterwards.
        filterPane.insertBefore(catFilterBarEl, filterPane.firstChild);
        buildCategoryFilterHTML();
      } else if (dataChanged) {
        const isInputFocused = catFilterBarEl.contains(document.activeElement);
        if (!isInputFocused) {
          buildCategoryFilterHTML();
        }
      }
    } else if (catFilterBarEl && catFilterBarEl.parentNode) {
      catFilterBarEl.parentNode.removeChild(catFilterBarEl);
    }
  }

  // MutationObserver to watch DOM updates as React renders
  let throttleTimer = null;
  const observer = new MutationObserver(() => {
    if (throttleTimer) return;
    throttleTimer = setTimeout(() => {
      throttleTimer = null;
      updateThemeColors();
      updateRouteState();
      injectBrandHeader();
      injectUserProfile();
      decorateSearchInputs();
      updateLibraryCategoryFilter();
      adjustSourceBrowseSpacing();
    }, 150);
  });

  function init() {
    updateThemeColors();
    updateRouteState();
    injectBrandHeader();
    injectUserProfile();
    decorateSearchInputs();
    handleScroll();
    updateLibraryCategoryFilter();
    adjustSourceBrowseSpacing();

    const rootEl = document.getElementById('root') || document.body;
    observer.observe(rootEl, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['class']
    });
    // React portals (MUI Drawer/Modal/Menu — e.g. the Filter/Sort/Display bottom-sheet
    // popup) mount as direct children of <body>, outside #root, so the observer above
    // never sees them appear. Watch body's direct children (no subtree) just to catch
    // that — subtree:true here would fire on every deep mutation anywhere in the app,
    // including MUI's own Collapse-height transitions, and racing our DOM writes against
    // those caused visible layout glitches in open popups.
    observer.observe(document.body, { childList: true });
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-mui-color-scheme']
    });

    setInterval(updateRouteState, 250);

    const isSignedOut = localStorage.getItem('suwayomi-signed-out') === 'true';
    if (isSignedOut) {
      showLoginPage();
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
