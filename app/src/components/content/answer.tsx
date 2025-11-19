import "preact";
import "md-wrapper/loader";
import { ojListView } from "ojs/ojlistview";
import "ojs/ojavatar";

declare global {
  namespace preact.JSX {
    interface IntrinsicElements {
      "md-wrapper": any;
    }
  }
}

type Props = {
  item: ojListView.ItemTemplateContext;
  sim: boolean;
};

const enhanceCitations = (text: string) => {
  return text.replace(/\[(\d+)\]/g, (_match, num) => {
    return [
      `<span`,
      `  style="cursor:pointer; color:inherit; text-decoration:none;"`,
      `  role="button" aria-expanded="false"`,
      `  onclick="var n=this.nextElementSibling; var exp=this.getAttribute('aria-expanded')==='true'; n.style.display = exp ? 'none' : 'inline'; this.setAttribute('aria-expanded', (!exp).toString());"`,
      `>[${num}]</span>`,
      `<span`,
      `  style="display:none; color:#6b7280; font-size:0.85em; margin-left:4px;"`,
      `>Source details not available yet.</span>`
    ].join('');
  });
};

export const Answer = ({ item, sim }: Props) => {
  const originalAnswer = item.data.answer;
  const enhancedAnswer = enhanceCitations(originalAnswer);
  return (
    <>
      {sim && (
        <li class="oj-flex demo-sim-answer-layout oj-bg-danger-30">
          <div class="oj-flex-item oj-flex-bar">
            <div class="oj-sm-justify-content-flex-end oj-flex-bar-middle oj-sm-padding-2x demo-copy-paste">
              <md-wrapper
                id="TestingOne"
                class="oj-sm-width-full"
                markdown={enhancedAnswer}
              />
            </div>
            <div class="oj-flex-bar-end">
              <oj-avatar
                size="sm"
                role="presentation"
                src="styles/images/placeholder-female-02.png"
                background="orange"
              ></oj-avatar>
            </div>
          </div>
        </li>
      )}
      {!sim && (
        <li class="oj-flex demo-answer-layout">
          <div class="oj-flex-item">
            <div class="oj-sm-justify-content-flex-end oj-sm-padding-2x-end oj-sm-12 demo-copy-paste">
              <md-wrapper
                id="TestingOne"
                class="oj-sm-12"
                markdown={enhancedAnswer}
              />
            </div>
            {/* <div class="oj-flex-bar-end">
              <oj-avatar
                initials="A"
                size="sm"
                role="presentation"
                background="orange"
              ></oj-avatar>
            </div> */}
          </div>
        </li>
      )}
    </>
  );
};
