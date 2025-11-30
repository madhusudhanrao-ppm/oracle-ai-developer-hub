import { Box, Button, Snackbar, Stack, TextField, Typography } from "@mui/material";
import { useEffect, useState, useContext } from "react";
import { useForm } from "react-hook-form";
import { useStomp } from "./stompHook";
import IdentityContext from "./IdentityContext";

function Summary() {
  const identity = useContext(IdentityContext);
  const { register, handleSubmit, reset } = useForm();
  const [waiting, setWaiting] = useState(false);
  const [showError, setShowError] = useState(false);
  const [errorMessage, setErrorMessage] = useState();
  const [summary, setSummary] = useState("");
  const { subscribe, unsubscribe, isConnected } = useStomp();

  useEffect(() => {
    if (isConnected) {
      subscribe("/user/queue/summary", (message) => {
        if (message.errorMessage.length > 0) {
          setErrorMessage(message.errorMessage);
          setShowError(true);
        } else {
          console.log("/user/queue/summary");
          console.log(message);
          setSummary(message);
        }
      });
    }

    return () => {
      unsubscribe("/user/queue/summary");
    };
  }, [isConnected]);

  const onSubmit = async (data) => {
    setWaiting(true);
    const formData = new FormData();
    formData.append("file", data.file[0]);

    const res = await fetch("/api/upload", {
      method: "POST",
      body: formData,
      headers: { conversationId: identity, modelId: "n/a" },
    });
    const responseData = await res.json();
    const { content, errorMessage } = responseData;
    if (errorMessage.length) {
      setErrorMessage(errorMessage);
      setShowError(true);
    } else {
      console.log(content);
      setSummary(content);
    }
    setWaiting(false);
    reset();
  };
  return (
    <Box>
      <form onSubmit={handleSubmit(onSubmit)}>
        <Stack alignItems={"center"}>
          <TextField style={{ width: "30rem" }} type="file" {...register("file")} />

          <Button disabled={waiting} type="submit">
            Submit
          </Button>
        </Stack>
      </form>
      {summary.length !== 0 && <Typography>{summary}</Typography>}
      <Snackbar
        open={showError}
        anchorOrigin={{ vertical: "bottom", horizontal: "center" }}
        autoHideDuration={6000}
        onClose={() => {
          setErrorMessage();
          setShowError(false);
        }}
        message={errorMessage}
      />
    </Box>
  );
}

export default Summary;
