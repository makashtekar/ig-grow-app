/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        obsidian: "#0D0F14",
        surfaceCard: "#161922",
        surfaceElevated: "#1E222E",
        outlineSubtle: "#2A3040",
        coral: "#FF5E62",
        magenta: "#C837AB",
        amberBrand: "#F77737",
        emeraldStatus: "#22C55E",
        warningStatus: "#F59E0B",
        roseStatus: "#EF4444",
      },
    },
  },
  plugins: [],
}
